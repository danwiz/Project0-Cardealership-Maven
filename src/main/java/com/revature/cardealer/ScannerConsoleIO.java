package com.revature.cardealer;

import java.io.PrintStream;
import java.util.Objects;
import java.util.Scanner;

/** Scanner/PrintStream adapter for the console application. */
public final class ScannerConsoleIO implements ConsoleIO {
    private final Scanner scanner;
    private final PrintStream output;

    public ScannerConsoleIO(Scanner scanner, PrintStream output) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public String readLine() {
        return scanner.nextLine();
    }

    @Override
    public void writeLine(String value) {
        output.println(value);
    }
}
