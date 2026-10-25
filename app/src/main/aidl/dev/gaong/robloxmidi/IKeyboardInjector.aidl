package dev.gaong.robloxmidi;

interface IKeyboardInjector {
    void destroy() = 16777114;
    boolean sendKey(int keyCode, boolean down, boolean shift) = 1;
    void releaseAll() = 2;
    String getBackendStatus() = 3;
}
