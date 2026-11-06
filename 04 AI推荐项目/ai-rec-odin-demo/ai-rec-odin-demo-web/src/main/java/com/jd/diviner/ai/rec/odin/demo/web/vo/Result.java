package com.jd.diviner.ai.rec.odin.demo.web.vo;

public class Result<T> {

    public static final int SUCC_CODE = 0;

    private final int code;

    private final T data;

    private final String msg;

    public Result(T data) {
        this(data, "");
    }

    public Result(T data, String msg) {
        this(SUCC_CODE, data, msg);
    }

    public Result(int code, T data, String msg) {
        this.data = data;
        this.msg = msg;
        this.code = code;
    }

    public T getData() {
        return data;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
