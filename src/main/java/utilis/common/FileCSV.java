package utilis.common;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.FileWriter;
import java.util.List;
import java.util.logging.Logger;

public class FileCSV {

    private FileWriter fileWriter;
    private CSVPrinter csvPrinter;

    public FileCSV(String filename, List<String> header) {

        this.fileWriter = null;
        this.csvPrinter = null;

        try {

            this.fileWriter = new FileWriter(filename + ".csv");
            this.csvPrinter = new CSVPrinter(fileWriter, CSVFormat.DEFAULT);

        } catch (Exception e) {
            printErrorClosingCSVFile(e);
        }

        write(header);
    }

    public void write(List<String> record) {

        try {

            this.csvPrinter.printRecord(record);

        } catch (Exception e) {
            printErrorClosingCSVFile(e);
        }
    }

    public void close() {

        ResourceManagement.close(csvPrinter);
        ResourceManagement.close(fileWriter);
    }

    private void printErrorClosingCSVFile(Exception exception) {

        Logger.getLogger(FileCSV.class.getName()).severe(exception.getMessage());
        close();

        System.exit(exception.hashCode());
    }
}