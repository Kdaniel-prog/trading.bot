package kd.trading.bot.telegram.eventType;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;


@Getter
public class TradeClosedUpdateEvent extends ApplicationEvent {
    private final String message;

    public TradeClosedUpdateEvent(Object source, String message) {
        super(source);
        this.message = message;
    }

}
