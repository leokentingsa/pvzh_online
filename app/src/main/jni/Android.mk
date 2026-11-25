LOCAL_PATH := $(call my-dir)
include $(LOCAL_PATH)/frida-devkit/includes.mk

include $(CLEAR_VARS)
LOCAL_MODULE := frida-core
LOCAL_SRC_FILES := frida-devkit/libfrida-core.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/frida-devkit $(FRIDA_DEVKIT_INCLUDE_DIRS)
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := pvzhfrida
LOCAL_SRC_FILES := frida_bridge.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/frida-devkit $(FRIDA_DEVKIT_INCLUDE_DIRS)
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -frtti
LOCAL_STATIC_LIBRARIES := frida-core
LOCAL_LDLIBS := -llog -landroid -ldl -lz -lm
include $(BUILD_SHARED_LIBRARY)
