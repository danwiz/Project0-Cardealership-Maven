package com.revature.cardealer;

/** Minimal text interface used by console command handlers. */
public interface ConsoleIO {
    String readLine();
    void writeLine(String value);
}
