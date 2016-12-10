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
            if (selector.select() == 0) {
                continue;
            }

            Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
            while (selectedKeys.hasNext()) {
                SelectionKey key = selectedKeys.next();

                try {
                    if (!key.isValid()) {
                        key.cancel();
                        key.channel().close();
                        logger.debug("Closed invalid key: {}", key);
                        continue;
                    }
                    if (key.isAcceptable()) {
                        ServerSocketChannel localServerChannel = (ServerSocketChannel) key.channel();
                        SocketChannel clientChannel = localServerChannel.accept();
                        logger.info("Received connection from {}", clientChannel.getRemoteAddress());

                        ProxyMember remoteServer = new ProxyMember();
                        ProxyMember client = new ProxyMember();

                        remoteServer.setPair(client);
                        client.setPair(remoteServer);

                        logger.debug("Trying connect to {}:{}", this.remoteAddr, this.remotePort);
                        try {
                            SocketChannel remoteServerChannel = SocketChannel.open(new InetSocketAddress(this.remoteAddr, this.remotePort));
                            remoteServerChannel.configureBlocking(false);
                            remoteServerChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, remoteServer);
                            logger.debug("Connected to {}", remoteServerChannel.getRemoteAddress());
                        } catch (IOException | UnresolvedAddressException | java.nio.channels.UnsupportedAddressTypeException ex) {
                            clientChannel.close();
                            continue;
                        }

                        clientChannel.configureBlocking(false);
                        clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, client);
                    }

                    if (key.isReadable()) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        ((ProxyMember) key.attachment()).handleRead(channel);
                    }

                    if (key.isWritable()) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        ((ProxyMember) key.attachment()).handleWrite(channel);
                    }
                } catch (IOException ex) {
                    logger.info("Lost client: {}", ((SocketChannel) key.channel()).getRemoteAddress());
                    key.cancel();
                    key.channel().close();
                    logger.debug("IOException", ex);
                } catch (Exception ex) {
                    logger.error("Some error occurred", ex);
                }
                selectedKeys.remove();
            }
        }
    }

    private final int listenPort;
    private final InetAddress remoteAddr;
    private final int remotePort;

    public PortForwarder(int listenPort, InetAddress remoteAddr, int remotePort) {
        this.listenPort = listenPort;
        this.remoteAddr = remoteAddr;
        this.remotePort = remotePort;
    }
}