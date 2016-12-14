package ru.nsu.likhachev.network.portforwarder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
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

    ProxyMember(SocketChannel channel) {
        this.channel = channel;
    }

    void handleRead() throws IOException {
        int read = this.channel.read(this.pair.buffer);
        if (read <= 0) {
            if (read < 0) {
                throw new OutputShutdownException(this);
            }
            return;
        }
        logger.debug("Read {} bytes from {}", read, this.channel.getRemoteAddress());
    }

    void handleWrite() throws IOException {
        if (this.buffer.position() == 0 || (this.channel.validOps() & SelectionKey.OP_WRITE) == 0) {
            return;
        }
        this.buffer.flip();
        logger.debug("Sent {} bytes to {}", this.channel.write(this.buffer), this.channel.getRemoteAddress());
        this.buffer.compact();
    }

    void setPair(ProxyMember pair) {
        this.pair = pair;
    }

    void close() throws IOException {
        this.channel.close();
        this.pair.channel.close();
    }

    void registerReadWrite(Selector selector) throws ClosedChannelException {
        this.channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
    }

    ProxyMember getPair() {
        return this.pair;
    }

    SocketChannel getChannel() {
        return this.channel;
    }
}
