LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := galaxy_codec_probe
LOCAL_SRC_FILES := galaxy_codec_probe.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
LOCAL_LDLIBS := -lmediandk -landroid
include $(BUILD_SHARED_LIBRARY)
