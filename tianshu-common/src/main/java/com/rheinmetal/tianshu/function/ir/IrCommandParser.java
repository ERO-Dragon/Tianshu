package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ir.core.IRParseResult;

public interface IrCommandParser {
    IRParseResult parse(String text, boolean fastIr);

    String formatPreview(IRParseResult result);

    static IrCommandParser unavailable() {
        return new IrCommandParser() {
            @Override
            public IRParseResult parse(String text, boolean fastIr) {
                return null;
            }

            @Override
            public String formatPreview(IRParseResult result) {
                return "";
            }
        };
    }
}
