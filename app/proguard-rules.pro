# SideSpeed's shell-uid helper is started by name from app_process:
#   app_process /system/bin ... io.github.devjardins.sidespeed.helper.HelperMain --apk=...
# Nothing in the app references it, so R8 must keep the class name and its static main.
-keep class io.github.devjardins.sidespeed.helper.HelperMain {
    public static void main(java.lang.String[]);
    public static final io.github.devjardins.sidespeed.helper.HelperMain INSTANCE;
}
