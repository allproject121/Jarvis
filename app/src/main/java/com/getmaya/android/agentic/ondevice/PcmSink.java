package com.getmaya.android.agentic.ondevice;

public interface PcmSink {
    boolean onPcm(byte[] buffer, int length);
}
