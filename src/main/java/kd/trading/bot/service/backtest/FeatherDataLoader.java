package kd.trading.bot.service.backtest;

import kd.trading.bot.model.HistoricalCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FeatherDataLoader {

    @Value("${backtest.data.feather.directory:src/main/python/data}")
    private String featherDataDirectory;

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    /**
     * Load historical data from feather files using Python bridge
     */
    public List<HistoricalCandle> loadHistoricalData(String symbol, String timeframe,
                                                     LocalDateTime startDate, LocalDateTime endDate) {

        String featherFile = String.format("%s-%s.feather", symbol, timeframe);
        File dataFile = new File(featherDataDirectory, featherFile);

        if (!dataFile.exists()) {
            log.warn("Feather file not found: {}", dataFile.getAbsolutePath());
            return Collections.emptyList();
        }

        try {
            // Create Python script to read feather and convert to CSV
            String pythonScript = createFeatherReaderScript(dataFile.getAbsolutePath(), startDate, endDate);

            // Execute Python script
            List<String> csvLines = executePythonScript(pythonScript);

            // Parse CSV lines to HistoricalCandle objects
            return parseCsvToCandles(csvLines);

        } catch (Exception e) {
            log.error("Failed to load feather data for {}-{}: {}", symbol, timeframe, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String createFeatherReaderScript(String featherPath, LocalDateTime startDate, LocalDateTime endDate) {
        return String.format("""
            import pandas as pd
            import sys
            from datetime import datetime
            
            try:
                # Read feather file
                df = pd.read_feather('%s')
                
                # Ensure date column exists and is datetime
                if 'date' in df.columns:
                    df['date'] = pd.to_datetime(df['date'])
                elif 'timestamp' in df.columns:
                    df['date'] = pd.to_datetime(df['timestamp'])
                    df = df.drop('timestamp', axis=1)
                else:
                    print("ERROR: No date/timestamp column found")
                    sys.exit(1)
                
                # Filter by date range
                start_date = datetime.fromisoformat('%s')
                end_date = datetime.fromisoformat('%s')
                
                df = df[(df['date'] >= start_date) & (df['date'] <= end_date)]
                
                # Sort by date
                df = df.sort_values('date')
                
                # Ensure required columns exist
                required_cols = ['date', 'open', 'high', 'low', 'close', 'volume']
                for col in required_cols:
                    if col not in df.columns:
                        print(f"ERROR: Missing column {col}")
                        sys.exit(1)
                
                # Convert to CSV format and print
                for _, row in df.iterrows():
                    print(f"{row['date'].isoformat()},{row['open']},{row['high']},{row['low']},{row['close']},{row['volume']}")
                
            except Exception as e:
                print(f"ERROR: {str(e)}")
                sys.exit(1)
            """,
                featherPath.replace("\\", "/"),
                startDate.toString(),
                endDate.toString()
        );
    }

    private List<String> executePythonScript(String script) throws IOException, InterruptedException {
        // Create temporary Python file
        File tempScript = File.createTempFile("feather_reader", ".py");
        tempScript.deleteOnExit();

        try (FileWriter writer = new FileWriter(tempScript)) {
            writer.write(script);
        }

        // Execute Python script
        ProcessBuilder pb = new ProcessBuilder(pythonExecutable, tempScript.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("ERROR:")) {
                    throw new RuntimeException("Python script error: " + line);
                }
                output.add(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Python script failed with exit code: " + exitCode);
        }

        return output;
    }

    private List<HistoricalCandle> parseCsvToCandles(List<String> csvLines) {
        return csvLines.stream()
                .filter(line -> !line.trim().isEmpty() && !line.startsWith("ERROR"))
                .map(this::parseCsvLine)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private HistoricalCandle parseCsvLine(String csvLine) {
        try {
            String[] parts = csvLine.split(",");
            if (parts.length != 6) {
                log.warn("Invalid CSV line: {}", csvLine);
                return null;
            }

            LocalDateTime timestamp = LocalDateTime.parse(parts[0], DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            double open = Double.parseDouble(parts[1]);
            double high = Double.parseDouble(parts[2]);
            double low = Double.parseDouble(parts[3]);
            double close = Double.parseDouble(parts[4]);
            double volume = Double.parseDouble(parts[5]);

            return HistoricalCandle.builder()
                    .timestamp(timestamp)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse CSV line: {} - {}", csvLine, e.getMessage());
            return null;
        }
    }

    /**
     * Alternative method: Direct Java feather reading (if you add Arrow dependency)
     * This would eliminate Python dependency but requires Apache Arrow Java
     */
    public List<HistoricalCandle> loadHistoricalDataDirect(String symbol, String timeframe,
                                                           LocalDateTime startDate, LocalDateTime endDate) {
        // This would require adding Apache Arrow dependency:
        // <dependency>
        //     <groupId>org.apache.arrow</groupId>
        //     <artifactId>flight-core</artifactId>
        //     <version>11.0.0</version>
        // </dependency>

        log.info("Direct feather reading not implemented - use loadHistoricalData() with Python bridge");
        return Collections.emptyList();
    }

    /**
     * Get available symbols from feather directory
     */
    public List<String> getAvailableSymbols() {
        File dataDir = new File(featherDataDirectory);
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            log.warn("Data directory not found: {}", featherDataDirectory);
            return Collections.emptyList();
        }

        return Arrays.stream(dataDir.listFiles((dir, name) -> name.endsWith(".feather")))
                .map(file -> {
                    String name = file.getName();
                    // Extract symbol from filename: BTC_USDT-1d.feather -> BTC_USDT
                    int dashIndex = name.lastIndexOf('-');
                    if (dashIndex > 0) {
                        return name.substring(0, dashIndex);
                    }
                    return name.replace(".feather", "");
                })
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get available timeframes for a symbol
     */
    public List<String> getAvailableTimeframes(String symbol) {
        File dataDir = new File(featherDataDirectory);
        if (!dataDir.exists()) return Collections.emptyList();

        String prefix = symbol + "-";
        return Arrays.stream(dataDir.listFiles((dir, name) ->
                        name.startsWith(prefix) && name.endsWith(".feather")))
                .map(file -> {
                    String name = file.getName();
                    // Extract timeframe: BTC_USDT-1d.feather -> 1d
                    int dashIndex = name.lastIndexOf('-');
                    int dotIndex = name.lastIndexOf('.');
                    if (dashIndex > 0 && dotIndex > dashIndex) {
                        return name.substring(dashIndex + 1, dotIndex);
                    }
                    return "unknown";
                })
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Check if feather file exists for symbol and timeframe
     */
    public boolean isDataAvailable(String symbol, String timeframe) {
        String fileName = String.format("%s-%s.feather", symbol, timeframe);
        File dataFile = new File(featherDataDirectory, fileName);
        return dataFile.exists() && dataFile.canRead();
    }

    /**
     * Get data range for a symbol/timeframe
     */
    public Map<String, LocalDateTime> getDataRange(String symbol, String timeframe) {
        Map<String, LocalDateTime> range = new HashMap<>();

        try {
            // Load a small sample to get date range
            LocalDateTime veryEarlyDate = LocalDateTime.of(2020, 1, 1, 0, 0);
            LocalDateTime now = LocalDateTime.now();

            List<HistoricalCandle> sample = loadHistoricalData(symbol, timeframe, veryEarlyDate, now);

            if (!sample.isEmpty()) {
                range.put("start", sample.get(0).getTimestamp());
                range.put("end", sample.get(sample.size() - 1).getTimestamp());
            }

        } catch (Exception e) {
            log.warn("Failed to get data range for {}-{}: {}", symbol, timeframe, e.getMessage());
        }

        return range;
    }
}