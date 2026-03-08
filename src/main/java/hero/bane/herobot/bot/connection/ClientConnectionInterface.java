package hero.bane.herobot.bot.connection;

import io.netty.channel.Channel;

public interface ClientConnectionInterface {
    void setChannel(Channel channel);
}
