LOCAL_PATH:= $(call my-dir)

########################################
# NCI Configuration
########################################
include $(CLEAR_VARS)

# $(shell mkdir -p $(TARGET_OUT_DATA)/onebillion/)
# $(shell cp -rf $(LOCAL_PATH)/assets.obb `pwd`/$(TARGET_OUT_DATA)/onebillion)

BUNDLE_ID := org.onebillion.onecourse.judges
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := onebillion
LOCAL_MODULE_CLASS := APPS
LOCAL_PRIVILEGED_MODULE := true
LOCAL_CERTIFICATE := platform
LOCAL_MODULE_PATH := $(TARGET_OUT)/priv-app
LOCAL_SRC_FILES := app-judgeMenu-release.apk
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)

$(shell rm -rf $(LOCAL_MODULE_PATH)/onebillion/)
# $(shell mkdir -p $(LOCAL_MODULE_PATH)/onebillion/)
# $(shell cp -rf $(LOCAL_PATH)/assets.obb `pwd`/$(LOCAL_MODULE_PATH)/onebillion)
#
$(shell rm -rf $(TARGET_OUT_DATA)/user/0/$(BUNDLE_ID))
# $(shell mkdir -p $(TARGET_OUT_DATA)/user/0/$(BUNDLE_ID)/files)
# $(shell cp -rf $(LOCAL_PATH)/assets $(TARGET_OUT_DATA)/user/0/$(BUNDLE_ID)/files)
#
# LOCAL_MODULE_PATH := $(TARGET_OUT_DATA)

include $(BUILD_PREBUILT)