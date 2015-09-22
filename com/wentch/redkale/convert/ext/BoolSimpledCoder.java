/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.wentch.redkale.convert.ext;

import com.wentch.redkale.convert.Reader;
import com.wentch.redkale.convert.SimpledCoder;
import com.wentch.redkale.convert.Writer;

/**
 *
 * @author zhangjx
 * @param <R>
 * @param <W>
 */
public final class BoolSimpledCoder<R extends Reader, W extends Writer> extends SimpledCoder<R, W, Boolean> {

    public static final BoolSimpledCoder instance = new BoolSimpledCoder();

    @Override
    public void convertTo(W out, Boolean value) {
        out.writeBoolean(value);
    }

    @Override
    public Boolean convertFrom(R in) {
        return in.readBoolean();
    }

}
