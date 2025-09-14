package kd.trading.bot.telegram.eventType;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.context.ApplicationEvent;

@Getter
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TradeClosedUpdateEvent extends ApplicationEvent {
    String message;

    public TradeClosedUpdateEvent(Object source, String message) {
        super(source);
        this.message = message;
    }

}
