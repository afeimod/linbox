package com.termux.x11;
import java.util.HashMap;
import android.content.Context;
import com.termux.x11.utils.TermuxX11ExtraKeys;

/**
 * 本文件由 scripts/gen_prefs.py 从 res/xml/preferences.xml 生成
 *（等价于参考实现的 generatePrefs 构建任务），勿手工修改；
 * 调整偏好项后请重新运行生成脚本。
 */
public class Prefs extends LoriePreferences.PrefsProto {
  public final ListPreference displayResolutionMode = new ListPreference("displayResolutionMode", "native", R.array.displayResolutionVariants, R.array.displayResolutionVariants);
  public final IntPreference displayScale = new IntPreference("displayScale", 100);
  public final ListPreference displayResolutionExact = new ListPreference("displayResolutionExact", "1280x1024", R.array.displayResolution, R.array.displayResolution);
  public final StringPreference displayResolutionCustom = new StringPreference("displayResolutionCustom", "1280x1024");
  public final BooleanPreference adjustResolution = new BooleanPreference("adjustResolution", false);
  public final BooleanPreference displayStretch = new BooleanPreference("displayStretch", false);
  public final BooleanPreference Reseed = new BooleanPreference("Reseed", true);
  public final BooleanPreference PIP = new BooleanPreference("PIP", false);
  // LinBox（fix9.6）：默认值按手机单手使用场景调整——
  // fullscreen/hideCutout 默认开（修复"被手机导航键挡住"），
  // 自带键盘栏默认关（用户要求默认关闭 X11 自带键盘）。
  // 存量安装由 LinBoxApp 的 linboxDefaultsRev 一次性迁移覆盖。
  public final BooleanPreference fullscreen = new BooleanPreference("fullscreen", true);
  public final ListPreference forceOrientation = new ListPreference("forceOrientation", "auto", R.array.forceOrientationVariants, R.array.forceOrientationVariants);
  public final BooleanPreference hideCutout = new BooleanPreference("hideCutout", true);
  public final BooleanPreference keepScreenOn = new BooleanPreference("keepScreenOn", true);
  public final ListPreference touchMode = new ListPreference("touchMode", "1", R.array.touchscreenInputModesEntries, R.array.touchscreenInputModesValues);
  public final BooleanPreference scaleTouchpad = new BooleanPreference("scaleTouchpad", true);
  public final BooleanPreference showStylusClickOverride = new BooleanPreference("showStylusClickOverride", false);
  public final BooleanPreference stylusIsMouse = new BooleanPreference("stylusIsMouse", false);
  public final BooleanPreference stylusButtonContactModifierMode = new BooleanPreference("stylusButtonContactModifierMode", false);
  public final BooleanPreference showMouseHelper = new BooleanPreference("showMouseHelper", false);
  public final BooleanPreference pointerCapture = new BooleanPreference("pointerCapture", false);
  public final ListPreference transformCapturedPointer = new ListPreference("transformCapturedPointer", "no", R.array.transformCapturedPointerEntries, R.array.transformCapturedPointerValues);
  public final IntPreference capturedPointerSpeedFactor = new IntPreference("capturedPointerSpeedFactor", 100);
  public final BooleanPreference tapToMove = new BooleanPreference("tapToMove", false);
  public final IntPreference touch_sensitivity = new IntPreference("touch_sensitivity", 1);
  public final BooleanPreference showAdditionalKbd = new BooleanPreference("showAdditionalKbd", false);
  public final BooleanPreference additionalKbdVisible = new BooleanPreference("additionalKbdVisible", false);
  public final BooleanPreference showIMEWhileExternalConnected = new BooleanPreference("showIMEWhileExternalConnected", true);
  public final BooleanPreference preferScancodes = new BooleanPreference("preferScancodes", true);
  public final BooleanPreference hardwareKbdScancodesWorkaround = new BooleanPreference("hardwareKbdScancodesWorkaround", true);
  public final BooleanPreference dexMetaKeyCapture = new BooleanPreference("dexMetaKeyCapture", false);
  public final BooleanPreference enableAccessibilityServiceAutomatically = new BooleanPreference("enableAccessibilityServiceAutomatically", false);
  public final BooleanPreference pauseKeyInterceptingWithEsc = new BooleanPreference("pauseKeyInterceptingWithEsc", false);
  public final BooleanPreference filterOutWinkey = new BooleanPreference("filterOutWinkey", false);
  public final BooleanPreference enforceCharBasedInput = new BooleanPreference("enforceCharBasedInput", false);
  public final BooleanPreference clipboardEnable = new BooleanPreference("clipboardEnable", true);
  public final BooleanPreference storeSecondaryDisplayPreferencesSeparately = new BooleanPreference("storeSecondaryDisplayPreferencesSeparately", false);
  public final BooleanPreference enableFloatBallMenu = new BooleanPreference("enableFloatBallMenu", false);
  public final BooleanPreference enableGlobalFloatBallMenu = new BooleanPreference("enableGlobalFloatBallMenu", false);
  public final BooleanPreference adjustHeightForEK = new BooleanPreference("adjustHeightForEK", false);
  public final BooleanPreference useTermuxEKBarBehaviour = new BooleanPreference("useTermuxEKBarBehaviour", false);
  public final IntPreference opacityEKBar = new IntPreference("opacityEKBar", 100);
  public final ListPreference swipeUpAction = new ListPreference("swipeUpAction", "no action", R.array.userActionsValues, R.array.userActionsValues);
  public final ListPreference swipeDownAction = new ListPreference("swipeDownAction", "toggle additional key bar", R.array.userActionsValues, R.array.userActionsValues);
  public final ListPreference volumeUpAction = new ListPreference("volumeUpAction", "no action", R.array.userActionsVolumeUpValues, R.array.userActionsVolumeUpValues);
  public final ListPreference volumeDownAction = new ListPreference("volumeDownAction", "no action", R.array.userActionsVolumeDownValues, R.array.userActionsVolumeDownValues);
  public final ListPreference notificationTapAction = new ListPreference("notificationTapAction", "open preferences", R.array.userActionsValues, R.array.userActionsValues);
  public final ListPreference notificationButton0Action = new ListPreference("notificationButton0Action", "open preferences", R.array.userActionsValues, R.array.userActionsValues);
  public final ListPreference notificationButton1Action = new ListPreference("notificationButton1Action", "exit", R.array.userActionsValues, R.array.userActionsValues);
  public final ListPreference mediaKeysAction = new ListPreference("mediaKeysAction", "no action", R.array.userActionsMediaKeyValues, R.array.userActionsMediaKeyValues);
  public final StringPreference extra_keys_config = new StringPreference("extra_keys_config", TermuxX11ExtraKeys.DEFAULT_IVALUE_EXTRA_KEYS);
  public final HashMap<String, Preference> keys = new HashMap<>() {{
    put("displayResolutionMode", displayResolutionMode);
    put("displayScale", displayScale);
    put("displayResolutionExact", displayResolutionExact);
    put("displayResolutionCustom", displayResolutionCustom);
    put("adjustResolution", adjustResolution);
    put("displayStretch", displayStretch);
    put("Reseed", Reseed);
    put("PIP", PIP);
    put("fullscreen", fullscreen);
    put("forceOrientation", forceOrientation);
    put("hideCutout", hideCutout);
    put("keepScreenOn", keepScreenOn);
    put("touchMode", touchMode);
    put("scaleTouchpad", scaleTouchpad);
    put("showStylusClickOverride", showStylusClickOverride);
    put("stylusIsMouse", stylusIsMouse);
    put("stylusButtonContactModifierMode", stylusButtonContactModifierMode);
    put("showMouseHelper", showMouseHelper);
    put("pointerCapture", pointerCapture);
    put("transformCapturedPointer", transformCapturedPointer);
    put("capturedPointerSpeedFactor", capturedPointerSpeedFactor);
    put("tapToMove", tapToMove);
    put("touch_sensitivity", touch_sensitivity);
    put("showAdditionalKbd", showAdditionalKbd);
    put("additionalKbdVisible", additionalKbdVisible);
    put("showIMEWhileExternalConnected", showIMEWhileExternalConnected);
    put("preferScancodes", preferScancodes);
    put("hardwareKbdScancodesWorkaround", hardwareKbdScancodesWorkaround);
    put("dexMetaKeyCapture", dexMetaKeyCapture);
    put("enableAccessibilityServiceAutomatically", enableAccessibilityServiceAutomatically);
    put("pauseKeyInterceptingWithEsc", pauseKeyInterceptingWithEsc);
    put("filterOutWinkey", filterOutWinkey);
    put("enforceCharBasedInput", enforceCharBasedInput);
    put("clipboardEnable", clipboardEnable);
    put("storeSecondaryDisplayPreferencesSeparately", storeSecondaryDisplayPreferencesSeparately);
    put("enableFloatBallMenu", enableFloatBallMenu);
    put("enableGlobalFloatBallMenu", enableGlobalFloatBallMenu);
    put("adjustHeightForEK", adjustHeightForEK);
    put("useTermuxEKBarBehaviour", useTermuxEKBarBehaviour);
    put("opacityEKBar", opacityEKBar);
    put("swipeUpAction", swipeUpAction);
    put("swipeDownAction", swipeDownAction);
    put("volumeUpAction", volumeUpAction);
    put("volumeDownAction", volumeDownAction);
    put("notificationTapAction", notificationTapAction);
    put("notificationButton0Action", notificationButton0Action);
    put("notificationButton1Action", notificationButton1Action);
    put("mediaKeysAction", mediaKeysAction);
    put("extra_keys_config", extra_keys_config);
  }};

  public Prefs(Context ctx) {
    super(ctx);
  }
}
