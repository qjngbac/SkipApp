# SkipApp 混淆规则 (纯原生，无第三方特殊反射库)
-keepattributes *Annotation*
-keepclassmembers class * {
    native <methods>;
}
