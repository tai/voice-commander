# VoiceCommander build helpers. The JDK comes from Android Studio (JBR);
# there is no system Java on this machine. The project lives on a network
# share, where Gradle's on-disk caches fail, so the project cache is forced
# to local disk (org.gradle.projectcachedir is not a real property; the CLI
# flag is the supported way).
JAVA_HOME ?= "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
GRADLE ?= JAVA_HOME=$(JAVA_HOME) ./gradlew --project-cache-dir /Users/tai/.gradle-vc
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
ADB ?= $(ANDROID_HOME)/platform-tools/adb
APK = app/build/outputs/apk/debug/app-debug.apk
DEVICE ?= ZY22JSFS7R

.PHONY: build test install run start stop logcat clean emu-start emu-stop

build:
	$(GRADLE) :app:assembleDebug

test:
	$(GRADLE) test

install: build
	$(ADB) -s $(DEVICE) install -r $(APK)

run: install
	$(ADB) -s $(DEVICE) shell am start -n dev.voicecommander/.ui.MainActivity

start:
	$(ADB) -s $(DEVICE) shell am start-foreground-service -n dev.voicecommander/.overlay.InputService

stop:
	$(ADB) -s $(DEVICE) shell am stopservice -n dev.voicecommander/.overlay.InputService

# Debug hook: feed a transcript as if recognized (drives the RAW→INTENT
# pipeline without a microphone).
inject:
	$(ADB) -s $(DEVICE) shell am broadcast -a dev.voicecommander.INJECT -n dev.voicecommander/.overlay.StartReceiver --es text "$(TEXT)"

emu-start:
	nohup $(ANDROID_HOME)/emulator/emulator -avd Medium_Phone -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 3072 >/tmp/emu.log 2>&1 &
	@echo "emulator booting (adb wait-for-device); DEVICE=emulator-5554 for the other targets"

emu-stop:
	$(ANDROID_HOME)/platform-tools/adb -s emulator-5554 emu kill 2>/dev/null || true

logcat:
	$(ADB) -s $(DEVICE) logcat | grep -iE "voicecommander|AndroidRuntime|SpeechRecognizer"

clean:
	$(GRADLE) clean