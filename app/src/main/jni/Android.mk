LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := mobileide-pty
LOCAL_SRC_FILES := mobileide_pty.c
LOCAL_CFLAGS := -O2 -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
