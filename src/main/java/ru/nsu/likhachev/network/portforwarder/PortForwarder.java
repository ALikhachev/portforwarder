package ru.nsu.likhachev.network.portforwarder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.*;
import java.util.Iterator;

/**
 * Copyright (c) 2016 Alexander Likhachev.
 */
public class PortForwarder {
    private static final Logger logger = LogManager.getLogger("PortForwarder");

    private final int listenPort;
    private final InetAddress remoteAddr;
    private final int remotePort;
    private boolean stop;

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: PortForwarder <listen-port> <remote-hostname> <remote-port>");
            return;
        }
        int lPort;
        try {
            lPort = Integer.parseInt(args[0]);
            if (lPort < 1 || lPort > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            System.out.println(args[0] + " is not a valid port number!");
            return;
        }
        InetAddress rAddr;
        try {
            rAddr = InetAddress.getByName(args[1]);
        } catch (UnknownHostException e) {
            System.out.println(args[1] + " is not a valid hostname");
            return;
        }
        int rPort;
        try {
            rPort = Integer.parseInt(args[2]);
            if (rPort < 1 || rPort > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            System.out.println(args[2] + " is not a valid port number!");
            return;
        }

        PortForwarder fwd = new PortForwarder(lPort, rAddr, rPort);
        logger.debug("PortForwarder constructed");
        try {
            fwd.run();
            logger.debug("PortForwarder run");
        } catch (IOException e) {
            logger.fatal("Cannot run PortForwarder", e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(fwd::stop));
    }

    private Selector setupConnection() throws IOException {
        Selector selector = Selector.open();

        ServerSocketChannel localServer = ServerSocketChannel.open();
        localServer.bind(new InetSocketAddress(this.listenPort));
        localServer.configureBlocking(false);
        localServer.register(selector, SelectionKey.OP_ACCEPT);
        logger.info("Listening to 0.0.0.0:{}", this.listenPort);

        return selector;
    }

    private void run() throws IOException {
        Selector selector = this.setupConnection();
        while (true) {
            if (this.stop) {
                logger.info("Bye...");
                break;
            }
            if (selector.select() == 0) {
                continue;
            }
            Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
            while (selectedKeys.hasNext()) {
                SelectionKey key = selectedKeys.next();
                try {
                    if (!key.isValid()) {
                        logger.debug("Closed invalid key: {}", key.channel());
                        key.cancel();
                        ((ProxyMember) key.attachment()).close();
                        continue;
                    }
                    if (key.isConnectable()) {
                        this.handleConnect(key, selector);
                    }
                    if (key.isAcceptable() && !this.handleAccept(key, selector)) {
                        continue;
                    }

                    if (key.isReadable()) {
                        this.handleRead(key);
                    }

                    if (key.isWritable()) {
                        this.handleWrite(key);
                    }
                } catch (OutputShutdownException ex) {
                    ProxyMember that = ex.getProxyMember();
                    ProxyMember pair = ex.getProxyMember().getPair();
                    that.scheduleShutdownInput();
                    pair.scheduleShutdownOutput();
                } catch (ClosedChannelException ex) {
                    key.cancel();
                    ((ProxyMember) key.attachment()).close(); // ensure that pair is closed
                } catch (IOException | CancelledKeyException ex) {
                    logger.error("Lost client: {} ({})",
                            ((SocketChannel) key.channel()).getRemoteAddress(),
                            ((SocketChannel) key.channel()).getLocalAddress(),
                            ex);
                    key.cancel();
                    ((ProxyMember) key.attachment()).close();
                } catch (Exception ex) {
                    logger.error("Some error occurred", ex);
                }
                selectedKeys.remove();
            }
        }
    }

    private void handleConnect(SelectionKey key, Selector selector) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ProxyMember attachment = (ProxyMember) key.attachment();
        if (channel.finishConnect()) {
            logger.debug("Connected to {} ({})", channel.getRemoteAddress(), channel.getLocalAddress());
            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            attachment.getPair().registerReadWrite(selector);
            logger.debug("Registered client ({}) to read & write operations", attachment.getPair().getChannel().getRemoteAddress());
        } else {
            attachment.close();
            logger.debug("Can't connect to {} ({}), discarding client connection", channel.getRemoteAddress(), channel.getLocalAddress());
        }
    }

    private boolean handleAccept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel localServerChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = localServerChannel.accept();
        logger.info("Received connection from {}", clientChannel.getRemoteAddress());

        ProxyMember remoteServer;
        ProxyMember client;

        logger.debug("Trying connect to {}:{}", this.remoteAddr, this.remotePort);
        try {
            SocketChannel remoteServerChannel = SocketChannel.open();
            remoteServerChannel.configureBlocking(false);
            remoteServerChannel.connect(new InetSocketAddress(this.remoteAddr, this.remotePort));
            remoteServer = new ProxyMember(remoteServerChannel);
            client = new ProxyMember(clientChannel);
            remoteServerChannel.register(selector, SelectionKey.OP_CONNECT, remoteServer);
        } catch (IOException | UnresolvedAddressException | java.nio.channels.UnsupportedAddressTypeException ex) {
            clientChannel.close();
            key.cancel();
            return false;
        }

        remoteServer.setPair(client);
        client.setPair(remoteServer);

        clientChannel.configureBlocking(false);
        clientChannel.register(selector, 0, clientChannel);
        return true;
    }

    private void handleWrite(SelectionKey key) throws IOException {
        ProxyMember attachment = (ProxyMember) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        if (attachment.isShutdownOutput()) {
            channel.shutdownOutput();
            logger.info("Shutdown output for {} ({})",
                    channel.getRemoteAddress(), channel.getLocalAddress());
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            if (key.interestOps() == 0) {
                logger.info("Client disconnected {} ({})",
                        channel.getRemoteAddress(), channel.getLocalAddress());
                attachment.close();
                key.cancel();
            }
        } else {
            attachment.handleWrite();
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        ProxyMember attachment = (ProxyMember) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        if (attachment.isShutdownInput()) {
            channel.shutdownInput();
            logger.info("Shutdown input for {} ({})",
                    channel.getRemoteAddress(), channel.getLocalAddress());
            key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
            if (key.interestOps() == 0) {
                logger.info("Client disconnected {} ({})",
                        channel.getRemoteAddress(), channel.getLocalAddress());
                attachment.close();
                key.cancel();
            }
        } else {
            attachment.handleRead();
        }
    }

    private void stop() {
        this.stop = true;
    }

    public PortForwarder(int listenPort, InetAddress remoteAddr, int remotePort) {
        this.listenPort = listenPort;
        this.remoteAddr = remoteAddr;
        this.remotePort = remotePort;
    }
}
