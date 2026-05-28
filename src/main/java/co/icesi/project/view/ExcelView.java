package co.icesi.project.view;

import co.icesi.project.model.SpeedRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ExcelView {

    public void exportResults(List<SpeedRecord> results, String outputPath) {

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {

            XSSFSheet sheet = workbook.createSheet("Average Speed Results");

            // --- Estilos ---
            XSSFCellStyle headerStyle = workbook.createCellStyle();
            XSSFFont headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)46, (byte)117, (byte)182}, null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            XSSFCellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setAlignment(HorizontalAlignment.CENTER);

            XSSFCellStyle speedStyle = workbook.createCellStyle();
            speedStyle.setAlignment(HorizontalAlignment.CENTER);
            XSSFDataFormat format = workbook.createDataFormat();
            speedStyle.setDataFormat(format.getFormat("0.00"));

            XSSFCellStyle altStyle = workbook.createCellStyle();
            altStyle.setAlignment(HorizontalAlignment.CENTER);
            altStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)235, (byte)241, (byte)250}, null));
            altStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            XSSFCellStyle altSpeedStyle = workbook.createCellStyle();
            altSpeedStyle.setAlignment(HorizontalAlignment.CENTER);
            altSpeedStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)235, (byte)241, (byte)250}, null));
            altSpeedStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            altSpeedStyle.setDataFormat(format.getFormat("0.00"));

            // --- Fila de metadatos ---
            Row metaRow = sheet.createRow(0);
            Cell metaCell = metaRow.createCell(0);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            metaCell.setCellValue("Generado: " + timestamp + "   |   Total rutas: " + results.size());

            // --- Header ---
            Row headerRow = sheet.createRow(1);
            String[] headers = {"Route ID", "Month", "Avg Speed (km/h)"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // --- Datos ---
            List<SpeedRecord> sorted = results.stream()
                    .sorted(java.util.Comparator.comparingInt(SpeedRecord::getLineId)
                            .thenComparing(SpeedRecord::getMonth))
                    .collect(java.util.stream.Collectors.toList());

            for (int i = 0; i < sorted.size(); i++) {
                SpeedRecord record = sorted.get(i);
                Row row = sheet.createRow(i + 2);
                boolean alt = i % 2 == 1;

                Cell routeCell = row.createCell(0);
                routeCell.setCellValue(record.getLineId());
                routeCell.setCellStyle(alt ? altStyle : dataStyle);

                Cell monthCell = row.createCell(1);
                monthCell.setCellValue(record.getMonth().toString());
                monthCell.setCellStyle(alt ? altStyle : dataStyle);

                Cell speedCell = row.createCell(2);
                speedCell.setCellValue(record.getAverageSpeed());
                speedCell.setCellStyle(alt ? altSpeedStyle : speedStyle);
            }

            // --- Autoajustar columnas ---
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 512);
            }

            // --- Guardar ---
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                workbook.write(fos);
            }

            System.out.println("[Excel] Resultados exportados a: " + outputPath);

        } catch (Exception e) {
            System.err.println("[Excel] Error al exportar: " + e.getMessage());
        }
    }
}
