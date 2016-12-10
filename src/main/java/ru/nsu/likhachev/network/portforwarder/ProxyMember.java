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

    void handleRead(SocketChannel channel) throws IOException {
        int read = channel.read(this.recvBuffer);
        if (read <= 0) {
            return;
        }
        logger.debug("Read {} bytes from {}", read, channel.getRemoteAddress());
        this.recvBuffer.flip();
        this.pair.sendBuffer.put(this.recvBuffer);
        this.recvBuffer.compact();
    }

    void handleWrite(SocketChannel channel) throws IOException {
        if (this.sendBuffer.position() == 0) {
            return;
        }
        this.sendBuffer.flip();
        logger.debug("Sent {} bytes to {}", channel.write(this.sendBuffer), channel.getRemoteAddress());
        this.sendBuffer.compact();
    }

    void setPair(ProxyMember pair) {
        this.pair = pair;
    }
}
