package com.newtv.console.mgr.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Excel 生成类
 *
 * @author liyi <li.yi@chinaott.net>
 */
public class ExcelBuild<T> {

    private Logger logger = LoggerFactory.getLogger(ExcelBuild.class);

    private static Charset GBK = Charset.forName("GBK");

    /**
     * Number 数值匹配
     */
    public static final String NUMBER_MATCHES = "-?(\\d+\\.)?\\d+";

    /**
     * 日期格式
     */
    private static final DateTimeFormatter DEFAULT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final SimpleDateFormat SIMPLE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    /**
     * 金额数据格式
     */
    private static final String MONEY_DATA_FORMAT = "_ %s* #,##0.00_ ;_ %s* -#,##0.00_ ;_ %s* \"-\"??_ ;_ @_ ";

    /**
     * 默认金额符号
     */
    private static final String DEFAULT_SYMBOL = "¥";

    /**
     * Sheet 分割最大行数
     */
    private static int SHEET_MAX_ROWS = 1000000;

    /**
     * 数据映射关联Map
     */
    private Map<String, BuildItem<T>> map;

    private Function<T, Object> nextIdFunction;

    public ExcelBuild() {
        this.map = new LinkedHashMap<>();
    }

    public void setNextIdFunction(Function<T, Object> nextIdFunction) {
        this.nextIdFunction = nextIdFunction;
    }

    public Function<T, Object> getNextIdFunction() {
        return nextIdFunction;
    }

    /**
     * 添加映射
     *
     * @param header       表头
     * @param function     function
     * @param money        是否为金额  可以为空
     * @param cellConsumer 单元格处理
     */
    public void addMapping(String header, Function<T, ?> function, boolean money, Consumer<CellWarp<T>> cellConsumer) {
        BuildItem buildItem = new BuildItem();
        buildItem.function = function;
        buildItem.money = money;
        buildItem.cellConsumer = cellConsumer;
        this.map.put(header, buildItem);
    }

    private static Map<Workbook, Map<String, CellStyle>> moneyCellStyles = new HashMap<>();

    /**
     * 金额数据格式化
     *
     * @param workbook
     * @param symbol
     * @return
     */
    public static CellStyle moneyFormat(Workbook workbook, String symbol) {
        if (!moneyCellStyles.containsKey(workbook)) {
            moneyCellStyles.put(workbook, new HashMap<>());
        }
        if (!moneyCellStyles.get(workbook).containsKey(symbol)) {
            String format = String.format(MONEY_DATA_FORMAT, symbol, symbol, symbol);
            CellStyle moneyStyle = workbook.createCellStyle();
            moneyStyle.setDataFormat(workbook.createDataFormat().getFormat(format));
            moneyStyle.setVerticalAlignment(CellStyle.VERTICAL_CENTER);
            moneyCellStyles.get(workbook).put(symbol, moneyStyle);
        }
        return moneyCellStyles.get(workbook).get(symbol);
    }

    /**
     * 生成列头
     *
     * @param workbook
     * @param sheet
     */
    private void buildHeard(Workbook workbook, Sheet sheet) {
        Font font = workbook.createFont();
        font.setBoldweight(XSSFFont.BOLDWEIGHT_BOLD);
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(font);
        headerStyle.setAlignment(CellStyle.ALIGN_CENTER);
        headerStyle.setVerticalAlignment(CellStyle.VERTICAL_CENTER);

        // 冻结首行
        sheet.createFreezePane(0, 1, 0, 1);
        Row header = sheet.createRow(0);
        int columnIndex = 0;
        // 添加列头
        for (Iterator<String> iterator = map.keySet().iterator(); iterator.hasNext(); ) {
            Cell cell = header.createCell(columnIndex++);
            cell.setCellStyle(headerStyle);
            cell.setCellValue(iterator.next());
        }
    }

    /**
     * 自适应列宽
     *
     * @param sheet
     */
    private void autoSizeColumn(Sheet sheet) {
        try {
            if (sheet instanceof SXSSFSheet) {
                ((SXSSFSheet) sheet).trackAllColumnsForAutoSizing();
            }
            Iterator<String> iterator = map.keySet().iterator();
            for (int i = 0; i < map.keySet().size(); i++) {
                sheet.autoSizeColumn(i);
                int headerWidth = (iterator.next().getBytes(GBK).length + 1) * 256;
                if (sheet.getColumnWidth(i) < headerWidth) {
                    sheet.setColumnWidth(i, headerWidth);
                }
            }
            if (sheet instanceof SXSSFSheet) {
                ((SXSSFSheet) sheet).untrackAllColumnsForAutoSizing();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 生成表头<br>
     * 生成行数据
     *
     * @param workbook
     * @param list
     */
    public void build(Workbook workbook, List<T> list) {

        CellWarp<T> cellWarp = new CellWarp<>();
        cellWarp.setWorkbook(workbook);
        // 金额数据格式化
        CellStyle moneyStyle = ExcelBuild.moneyFormat(workbook, DEFAULT_SYMBOL);

        // 生成列头
        buildHeard(workbook, workbook.createSheet());

        // 添加数据
        for (int i = 0; i < list.size(); i++) {
            buildRow(workbook, list.get(i), cellWarp, moneyStyle, i);
        }

        autoSizeColumn(workbook.getSheetAt(workbook.getNumberOfSheets() - 1));
        moneyCellStyles.remove(workbook);
    }


    /**
     * 生成表头<br>
     * 生成行数据
     *
     * @param workbook
     * @param nextFunction
     */
    public void build(Workbook workbook, Function<Object, List<T>> nextFunction) {
        Objects.requireNonNull(nextIdFunction, "NextIdFunction not null");

        FunctionPullRunnable functionPullRunnable = new FunctionPullRunnable(nextFunction);

        Thread thread = Executors.defaultThreadFactory().newThread(functionPullRunnable);
        thread.setDaemon(true);
        thread.start();

        CellWarp<T> cellWarp = new CellWarp<>();
        cellWarp.setWorkbook(workbook);
        // 金额数据格式化
        CellStyle moneyStyle = ExcelBuild.moneyFormat(workbook, DEFAULT_SYMBOL);

        // 生成列头
        buildHeard(workbook, workbook.createSheet());

        int rowIndex = 0;
        while (true) {
            T model = null;
            try {
                model = functionPullRunnable.queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (model == null) {
                if (functionPullRunnable.finished) {
                    break;
                }
                continue;
            }
            buildRow(workbook, model, cellWarp, moneyStyle, rowIndex);
            rowIndex++;
        }

        autoSizeColumn(workbook.getSheetAt(workbook.getNumberOfSheets() - 1));
        moneyCellStyles.remove(workbook);
    }

    /**
     * 生成Excel 行数据
     *
     * @param workbook
     * @param model
     * @param cellWarp
     * @param moneyStyle
     * @param rowIndex
     */
    private void buildRow(Workbook workbook, T model, CellWarp<T> cellWarp, CellStyle moneyStyle, int rowIndex) {
        int currentRow = rowIndex % SHEET_MAX_ROWS;
        Sheet sheet = workbook.getSheetAt(workbook.getNumberOfSheets() - 1);
        if (rowIndex > 0 && currentRow == 0) {
            autoSizeColumn(sheet);
            sheet = workbook.createSheet();
            // 生成列头
            buildHeard(workbook, sheet);
        }

        Row row = sheet.createRow(currentRow + 1);

        int columnIndex = 0;
        cellWarp.setModel(model);
        for (Iterator<String> iterator = map.keySet().iterator(); iterator.hasNext(); ) {
            String key = iterator.next();
            Cell cell = row.createCell(columnIndex++);
            cellWarp.setCell(cell);
            BuildItem<T> item = map.get(key);
            Object value = item.function.apply(model);
            if (Objects.isNull(value)) {
                continue;
            }
            if (value instanceof Integer) {
                if (item.money) {
                    Integer valueInt = (Integer) value;
                    double valueDouble = BigDecimal.valueOf(valueInt).divide(new BigDecimal(100)).setScale(2, RoundingMode.HALF_UP).doubleValue();
                    cell.setCellValue(valueDouble);
                } else {
                    cell.setCellValue((Integer) value);
                }
            } else if (value instanceof Long) {
                if (item.money) {
                    Long valueLong = (Long) value;
                    double valueDouble = BigDecimal.valueOf(valueLong).divide(new BigDecimal(100)).setScale(2, RoundingMode.HALF_UP).doubleValue();
                    cell.setCellValue(valueDouble);
                } else {
                    cell.setCellValue((Long) value);
                }
            } else if (value instanceof Double) {
                cell.setCellValue((Double) value);
            } else if (value instanceof Float) {
                cell.setCellValue((Float) value);
            } else if (value instanceof LocalDateTime) {
                LocalDateTime date = (LocalDateTime) value;
                cell.setCellValue(date.format(DEFAULT_DATE_FORMAT));
            } else if (value instanceof LocalDate) {
                LocalDate date = (LocalDate) value;
                cell.setCellValue(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
            } else if (value instanceof String && item.money && value.toString().matches(NUMBER_MATCHES)) {
                cell.setCellValue(Double.valueOf(value.toString()));
                cell.setCellStyle(moneyStyle);
            } else if (value instanceof Date) {
                Date date = (Date) value;
                cell.setCellValue(SIMPLE_DATE_FORMAT.format(date));
            } else {
                cell.setCellValue(value.toString());
            }
            if (item.money && value instanceof Number) {
                cell.setCellStyle(moneyStyle);
            }
            // 自定义单元格
            if (Objects.nonNull(item.cellConsumer)) {
                item.cellConsumer.accept(cellWarp);
            }
        }
    }


    private class BuildItem<T> {
        private Function<T, ?> function;

        private boolean money;

        private Consumer<CellWarp<T>> cellConsumer;
    }

    private class FunctionPullRunnable implements Runnable {
        BlockingQueue<T> queue;
        Boolean finished;
        Function<Object, List<T>> nextFunction;

        public FunctionPullRunnable(Function<Object, List<T>> nextFunction) {
            this.queue = new LinkedBlockingQueue();
            this.finished = false;
            this.nextFunction = nextFunction;
        }

        @Override
        public void run() {
            Object nextId = null;
            while (true) {
                List<T> list = nextFunction.apply(nextId);
                if (Objects.isNull(list) || list.isEmpty()) {
                    finished = true;
                    break;
                }
                logger.info("NextId:{} Size:{} ", nextId, list.size());
                list.stream().forEach(queue::add);
                nextId = nextIdFunction.apply(list.get(list.size() - 1));
            }
        }

    }

}
