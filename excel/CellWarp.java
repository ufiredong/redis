package com.newtv.console.mgr.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Excel Build 单元格包装
 * @author liyi <li.yi@chinaott.net>
 */
public class CellWarp<T> {

    private T model;

    private Cell cell;

    private Workbook workbook;

    public T getModel() {
        return model;
    }

    public void setModel(T model) {
        this.model = model;
    }

    public Cell getCell() {
        return cell;
    }

    public void setCell(Cell cell) {
        this.cell = cell;
    }

    public Workbook getWorkbook() {
        return workbook;
    }

    public void setWorkbook(Workbook workbook) {
        this.workbook = workbook;
    }
}
