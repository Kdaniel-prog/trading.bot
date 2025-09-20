package kd.trading.bot.config.backtest;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "backtest")
@Data
@Setter
@Getter
public class BacktestConfigProperties {
    private String symbol;
    private String timeframe;
    private String startDate;
    private String endDate;

    private Data data = new Data();
    private Run run = new Run();
    private Initial initial = new Initial();
    private Fee fee = new Fee();
    private Position position = new Position();
    private Risk risk = new Risk();
    private Analysis analysis = new Analysis();
    private Performance performance = new Performance();

    @lombok.Data
    public static class Data {
        private Feather feather = new Feather();

        @lombok.Data
        public static class Feather {
            private String directory = "src/main/python/data";
        }
    }

    @lombok.Data
    public static class Run {
        private On on = new On();

        @lombok.Data
        public static class On {
            private boolean startup = false;
        }
    }

    @lombok.Data
    public static class Initial {
        private double balance = 10000.0;
    }

    @lombok.Data
    public static class Fee {
        private double rate = 0.0004;
    }

    @lombok.Data
    public static class Position {
        private Size size = new Size();

        @lombok.Data
        public static class Size {
            private double percent = 0.1;
        }
    }

    @lombok.Data
    public static class Risk {
        private Stop stop = new Stop();
        private Take take = new Take();
        private Max max = new Max();

        @lombok.Data
        public static class Stop {
            private Loss loss = new Loss();

            @lombok.Data
            public static class Loss {
                private double percent = 3.0;
            }
        }

        @lombok.Data
        public static class Take {
            private Profit profit = new Profit();

            @lombok.Data
            public static class Profit {
                private double percent = 6.0;
            }
        }

        @lombok.Data
        public static class Max {
            private Holding holding = new Holding();

            @lombok.Data
            public static class Holding {
                private int hours = 168;
            }
        }
    }

    @lombok.Data
    public static class Analysis {
        private Enable enable = new Enable();

        @lombok.Data
        public static class Enable {
            private Rule rule = new Rule();
            private Multi multi = new Multi();
            private boolean optimization = false;

            @lombok.Data
            public static class Rule {
                private boolean analysis = true;
            }

            @lombok.Data
            public static class Multi {
                private Symbol symbol = new Symbol();

                @lombok.Data
                public static class Symbol {
                    private boolean analysis = true;
                }
            }
        }
    }

    @lombok.Data
    public static class Performance {
        private Benchmark benchmark = new Benchmark();
        private Parallel parallel = new Parallel();
        private Cache cache = new Cache();

        @lombok.Data
        public static class Benchmark {
            private String symbol = "BTC_USDT";
        }

        @lombok.Data
        public static class Parallel {
            private boolean processing = true;
        }

        @lombok.Data
        public static class Cache {
            private boolean results = true;
        }
    }

    // Convenience methods
    public String getFeatherDirectory() {
        return data.feather.directory;
    }

    public boolean isRunOnStartup() {
        return run.on.startup;
    }

    public double getInitialBalance() {
        return initial.balance;
    }

    public double getFeeRate() {
        return fee.rate;
    }

    public double getPositionSizePercent() {
        return position.size.percent;
    }

    public double getStopLossPercent() {
        return risk.stop.loss.percent;
    }

    public double getTakeProfitPercent() {
        return risk.take.profit.percent;
    }

    public int getMaxHoldingHours() {
        return risk.max.holding.hours;
    }

    public boolean isRuleAnalysisEnabled() {
        return analysis.enable.rule.analysis;
    }

    public boolean isMultiSymbolAnalysisEnabled() {
        return analysis.enable.multi.symbol.analysis;
    }

    public boolean isOptimizationEnabled() {
        return analysis.enable.optimization;
    }
}