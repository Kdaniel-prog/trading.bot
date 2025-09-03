package kd.trading.bot.mapper;

import kd.trading.bot.enums.*;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.OrderTradeUpdateDto;

import java.math.BigDecimal;

public class OrderMapper {

    public static OrderDto fromBinanceOrder(OrderTradeUpdateDto.Order o) {
        OrderDto dto = new OrderDto();

        dto.setOrderId(o.i);
        dto.setSymbol(o.s);
        dto.setClientOrderId(o.c);

        // státusz
        dto.setStatus(parseOrderStatus(o.X));

        // típusok
        dto.setType(parseOrderType(o.o));
        dto.setSide(parseOrderSide(o.S));
        dto.setPositionSide(parsePositionSide(o.ps));

        // mennyiségek
        dto.setPrice(toBigDecimal(o.p));
        dto.setAvgPrice(toBigDecimal(o.ap));
        dto.setOrigQty(toBigDecimal(o.q));
        dto.setExecutedQty(toBigDecimal(o.l));
        dto.setCumQty(toBigDecimal(o.z));
        dto.setCumQuote(toBigDecimal(o.L));

        // time in force
        dto.setTimeInForce(parseTimeInForce(o.f));

        // egyéb flag-ek
        dto.setReduceOnly(Boolean.TRUE.equals(o.R));
        dto.setClosePosition(false); // Binance nem küldi mindig → ha kell, külön logika
        dto.setPriceProtect(false);  // Binance-nál külön flag, itt default false

        dto.setUpdateTime(System.currentTimeMillis()); // Binance timestamp helyett lokális

        return dto;
    }

    private static BigDecimal toBigDecimal(String value) {
        return (value != null && !value.isEmpty()) ? new BigDecimal(value) : BigDecimal.ZERO;
    }

    private static OrderStatus parseOrderStatus(String status) {
        if (status == null) return null;
        return OrderStatus.valueOf(status); // pl. NEW, FILLED, CANCELED
    }

    private static OrderType parseOrderType(String type) {
        if (type == null) return null;
        return OrderType.valueOf(type); // pl. MARKET, LIMIT, STOP_MARKET
    }

    private static OrderSide parseOrderSide(String side) {
        if (side == null) return null;
        return OrderSide.valueOf(side); // BUY, SELL
    }

    private static PositionSide parsePositionSide(String pos) {
        if (pos == null) return PositionSide.BOTH;
        return PositionSide.valueOf(pos); // BOTH, LONG, SHORT
    }

    private static TimeInForce parseTimeInForce(String tif) {
        if (tif == null) return null;
        return TimeInForce.valueOf(tif); // pl. GTC, IOC, FOK
    }
}
