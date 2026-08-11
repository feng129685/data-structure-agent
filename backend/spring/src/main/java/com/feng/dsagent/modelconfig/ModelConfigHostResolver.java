package com.feng.dsagent.modelconfig;

import java.net.InetAddress;
import java.net.UnknownHostException;

interface ModelConfigHostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
