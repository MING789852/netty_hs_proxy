package com.xm.netty_proxy_common.key;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;


public interface Constants {
    AttributeKey<Channel> NEXT_CHANNEL=AttributeKey.newInstance("NEXT_CHANNEL");
}
