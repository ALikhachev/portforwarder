package ru.nsu.likhachev.network.portforwarder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import static ru.nsu.likhachev.network.portforwarder.Constants.BUFFER_SIZE;

/**
 * Copyright (c) 2016 Alexander Likhachev.
 */
class ProxyMember {
    private static final Logger logger = LogManager.getLogger("ProxyMember");
    private ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

    private ProxyMember pair;
    private final SocketChannel channel;
    private final Selector selector;
    private boolean shutdownInput = false;
    private boolean shutdownOutput = false;
    private boolean wantClose = false;
    private int interestOps = 0;

    ProxyMember(SocketChannel channel, Selector selector) {
        this.channel = channel;
        this.selector = selector;
    }

    void handleRead() throws IOException {
        int read = this.channel.read(this.pair.buffer);
        if (read <= 0) {
            if (read < 0) {
                throw new OutputShutdownException(this);
            }
            return;
        }
        this.pair.registerWrite();
        logger.debug("Read {} bytes from {}", read, this.channel.getRemoteAddress());
    }

    void handleWrite() throws IOException {
        this.buffer.flip();
        logger.debug("Sent {} bytes to {}", this.channel.write(this.buffer), this.channel.getRemoteAddress());
        this.buffer.compact();
        if (this.buffer.position() == 0) {
            this.unregisterWrite();
        }
    }

    void setPair(ProxyMember pair) {
        this.pair = pair;
    }

    void wantClose() {
        this.wantClose = true;
        this.pair.wantClose = true;
    }

    void close() throws IOException {
        logger.debug("Closed channel for {} ({})", this.channel.getRemoteAddress(), this.channel.getLocalAddress());
        this.channel.close();
        logger.debug("Closed channel for {} ({})", this.pair.channel.getRemoteAddress(), this.pair.channel.getLocalAddress());
        this.pair.channel.close();
    }

    void registerRead() throws IOException {
        logger.debug("Registered read {} ({})", this.channel.getRemoteAddress(), this.channel.getLocalAddress());
        this.interestOps |= SelectionKey.OP_READ;
        this.channel.register(this.selector, this.interestOps, this);
    }

    void unregisterRead() throws IOException {
        logger.debug("Unegistered read {} ({})", this.channel.getRemoteAddress(), this.channel.getLocalAddress());
        this.interestOps &= ~SelectionKey.OP_READ;
        this.channel.register(this.selector, this.interestOps, this);
    }

    void registerWrite() throws IOException {
        logger.debug("Registered write {} ({})", this.channel.getRemoteAddress(), this.channel.getLocalAddress());
        this.interestOps |= SelectionKey.OP_WRITE;
        this.channel.register(this.selector, this.interestOps, this);
    }

    void unregisterWrite() throws IOException {
        logger.debug("Unegistered write {} ({})", this.channel.getRemoteAddress(), this.channel.getLocalAddress());
        this.interestOps &= ~SelectionKey.OP_WRITE;
        this.channel.register(this.selector, this.interestOps, this);
    }

    ProxyMember getPair() {
        return this.pair;
    }

    SocketChannel getChannel() {
        return this.channel;
    }

    void scheduleShutdownInput() {
        this.shutdownInput = true;
    }

    void scheduleShutdownOutput() {
        this.shutdownOutput = true;
    }

    boolean isShutdownInput() {
        return this.shutdownInput;
    }

    boolean isShutdownOutput() {
        return this.buffer.position() == 0 && this.shutdownOutput;
    }

    boolean isReadyToClose() {
        return this.wantClose && this.pair.wantClose && this.buffer.position() == 0 && this.pair.buffer.position() == 0;
    }

    boolean hasData() {
        return this.buffer.position() > 0;
    }
}
