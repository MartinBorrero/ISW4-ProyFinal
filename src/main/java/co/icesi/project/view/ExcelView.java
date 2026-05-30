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

            // Estilo para encabezados (sin colores)
            XSSFCellStyle headerStyle = workbook.createCellStyle();
            XSSFFont headerFont = workbook.createFont();
            headerFont.setBold(true);

            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            // Estilo normal
            XSSFCellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setAlignment(HorizontalAlignment.CENTER);

            // Estilo para velocidades
            XSSFCellStyle speedStyle = workbook.createCellStyle();
            speedStyle.setAlignment(HorizontalAlignment.CENTER);

            XSSFDataFormat format = workbook.createDataFormat();
            speedStyle.setDataFormat(format.getFormat("0.00"));

            // Metadata
            Row metaRow = sheet.createRow(0);
            Cell metaCell = metaRow.createCell(0);

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            metaCell.setCellValue(
                    "Generado: " + timestamp +
                            "   |   Total rutas: " + results.size());

            // Headers
            Row headerRow = sheet.createRow(1);

            String[] headers = {
                    "Route ID",
                    "Month",
                    "Avg Speed (km/h)"
            };

            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Ordenar resultados
            List<SpeedRecord> sorted = results.stream()
                    .sorted(java.util.Comparator
                            .comparingInt(SpeedRecord::getLineId)
                            .thenComparing(SpeedRecord::getMonth))
                    .collect(java.util.stream.Collectors.toList());

            // Datos
            for (int i = 0; i < sorted.size(); i++) {

                SpeedRecord record = sorted.get(i);

                Row row = sheet.createRow(i + 2);

                Cell routeCell = row.createCell(0);
                routeCell.setCellValue(record.getLineId());
                routeCell.setCellStyle(dataStyle);

                Cell monthCell = row.createCell(1);
                monthCell.setCellValue(record.getMonth().toString());
                monthCell.setCellStyle(dataStyle);

                Cell speedCell = row.createCell(2);
                speedCell.setCellValue(record.getAverageSpeed());
                speedCell.setCellStyle(speedStyle);
            }

            // Ajustar columnas
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 512);
            }

            // Guardar archivo
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                workbook.write(fos);
            }

            System.out.println("[Excel] Resultados exportados a: " + outputPath);

        } catch (Exception e) {
            System.err.println("[Excel] Error al exportar: " + e.getMessage());
        }
    }
}