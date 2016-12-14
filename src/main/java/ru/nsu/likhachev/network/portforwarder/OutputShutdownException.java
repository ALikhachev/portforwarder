package ru.nsu.likhachev.network.portforwarder;

import java.io.IOException;

/**
 * Copyright (c) 2016 Alexander Likhachev.
 */
class OutputShutdownException extends IOException {
    private final ProxyMember proxyMember;

    OutputShutdownException(ProxyMember proxyMember) {
        this.proxyMember = proxyMember;
    }

    ProxyMember getProxyMember() {
        return this.proxyMember;
    }
}
