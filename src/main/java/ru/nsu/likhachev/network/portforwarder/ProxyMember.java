package ru.nsu.likhachev.network.portforwarder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static ru.nsu.likhachev.network.portforwarder.Constants.BUFFER_SIZE;

/**
 * Copyright (c) 2016 Alexander Likhachev.
 */
class ProxyMember {
    private static final Logger logger = LogManager.getLogger("ProxyMember");
    private ByteBuffer recvBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    private ByteBuffer sendBuffer = ByteBuffer.allocate(BUFFER_SIZE);

    private ProxyMember pair;
    private final SocketChannel channel;

    ProxyMember(SocketChannel channel) {
        this.channel = channel;
    }

    void handleRead() throws IOException {
        int read = this.channel.read(this.recvBuffer);
        if (read <= 0) {
            return;
        }
        logger.debug("Read {} bytes from {}", read, this.channel.getRemoteAddress());
        this.recvBuffer.flip();
        this.pair.sendBuffer.put(this.recvBuffer);
        this.recvBuffer.compact();
    }

    void handleWrite() throws IOException {
        if (this.sendBuffer.position() == 0) {
            return;
        }
        this.sendBuffer.flip();
        logger.debug("Sent {} bytes to {}", this.channel.write(this.sendBuffer), this.channel.getRemoteAddress());
        this.sendBuffer.compact();
    }

    void setPair(ProxyMember pair) {
        this.pair = pair;
    }

    void close() throws IOException {
        this.channel.close();
        this.pair.channel.close();
    }
}
