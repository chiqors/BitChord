#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>
#import <AppKit/AppKit.h>
#include <jni.h>
#include <mutex>

static JavaVM *g_vm = nullptr;
static std::mutex g_mutex;
static BOOL g_started = NO;
static jclass g_class = nullptr;
static jmethodID g_command = nullptr;
static NSString *g_artUrl = nil;
static MPMediaItemArtwork *g_artwork = nil;
static NSUInteger g_artGeneration = 0;
static NSURLSessionDataTask *g_artTask = nil;

static NSString *string(JNIEnv *env, jstring value) {
    if (!value) return @"";
    const jchar *chars = env->GetStringChars(value, nullptr);
    NSString *result = [[NSString alloc] initWithCharacters:reinterpret_cast<const unichar *>(chars)
                                                  length:env->GetStringLength(value)];
    env->ReleaseStringChars(value, chars);
    return result ?: @"";
}

static void command(NSInteger code, double position) {
    if (!g_vm) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return;
        attached = true;
    }
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_started && g_class && g_command) {
            env->CallStaticVoidMethod(g_class, g_command, (jint)code, (jdouble)position);
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

// An old download must never replace the cover of a newly selected track.
static void applyArtwork(NSData *data, NSUInteger generation) {
    @autoreleasepool {
        NSImage *image = data ? [[NSImage alloc] initWithData:data] : nil;
        if (!image || image.size.width <= 0 || image.size.height <= 0) return;
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_started || generation != g_artGeneration) return;
        g_artwork = [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size
            requestHandler:^NSImage *(CGSize) { return image; }];
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        NSMutableDictionary *info = [center.nowPlayingInfo mutableCopy];
        if (info) {
            info[MPMediaItemPropertyArtwork] = g_artwork;
            center.nowPlayingInfo = info;
        }
    }
}

static void loadArtwork(NSString *urlString) {
    if ([g_artUrl isEqualToString:urlString]) return;
    g_artUrl = [urlString copy];
    g_artwork = nil;
    [g_artTask cancel];
    g_artTask = nil;
    NSUInteger generation = ++g_artGeneration;
    if (!urlString.length) return;
    NSURL *url = [NSURL URLWithString:urlString];
    if (!url.scheme.length) url = [NSURL fileURLWithPath:urlString];
    if (url.isFileURL) {
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
            applyArtwork([NSData dataWithContentsOfURL:url], generation);
        });
    } else if ([url.scheme isEqualToString:@"https"] || [url.scheme isEqualToString:@"http"]) {
        NSURLRequest *request = [NSURLRequest requestWithURL:url cachePolicy:NSURLRequestUseProtocolCachePolicy timeoutInterval:15];
        g_artTask = [[NSURLSession sharedSession] dataTaskWithRequest:request
            completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
                if (!error && [(NSHTTPURLResponse *)response statusCode] == 200) applyArtwork(data, generation);
            }];
        [g_artTask resume];
    }
}

static void clearCommands() {
    MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];
    [center.playCommand removeTarget:nil];
    [center.pauseCommand removeTarget:nil];
    [center.togglePlayPauseCommand removeTarget:nil];
    [center.nextTrackCommand removeTarget:nil];
    [center.previousTrackCommand removeTarget:nil];
    [center.changePlaybackPositionCommand removeTarget:nil];
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopMacMedia_nativeStart(JNIEnv *env, jobject self) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_started) return JNI_TRUE;
        jclass type = env->GetObjectClass(self);
        g_command = env->GetStaticMethodID(type, "onCommand", "(ID)V");
        if (!g_command) { env->DeleteLocalRef(type); return JNI_FALSE; }
        g_class = static_cast<jclass>(env->NewGlobalRef(type));
        env->DeleteLocalRef(type);
        MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];
        clearCommands();
        center.playCommand.enabled = YES;
        [center.playCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *) { command(0, 0); return MPRemoteCommandHandlerStatusSuccess; }];
        center.pauseCommand.enabled = YES;
        [center.pauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *) { command(1, 0); return MPRemoteCommandHandlerStatusSuccess; }];
        center.togglePlayPauseCommand.enabled = YES;
        [center.togglePlayPauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *) { command(2, 0); return MPRemoteCommandHandlerStatusSuccess; }];
        center.nextTrackCommand.enabled = YES;
        [center.nextTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *) { command(3, 0); return MPRemoteCommandHandlerStatusSuccess; }];
        center.previousTrackCommand.enabled = YES;
        [center.previousTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *) { command(4, 0); return MPRemoteCommandHandlerStatusSuccess; }];
        center.changePlaybackPositionCommand.enabled = YES;
        [center.changePlaybackPositionCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
            MPChangePlaybackPositionCommandEvent *seek = (MPChangePlaybackPositionCommandEvent *)event;
            command(5, seek.positionTime);
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        g_started = YES;
        return JNI_TRUE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopMacMedia_nativeStop(JNIEnv *env, jobject) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_started = NO;
        clearCommands();
        ++g_artGeneration;
        [g_artTask cancel];
        g_artTask = nil;
        g_artUrl = nil;
        g_artwork = nil;
        if (g_class) env->DeleteGlobalRef(g_class);
        g_class = nullptr;
        g_command = nullptr;
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        center.playbackState = MPNowPlayingPlaybackStateStopped;
        center.nowPlayingInfo = nil;
        g_started = NO;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopMacMedia_nativeUpdate(JNIEnv *env, jobject, jboolean hasSong,
    jstring title, jstring artist, jstring album, jstring artUrl, jboolean playing,
    jdouble speed, jlong positionMs, jlong durationMs) {
    @autoreleasepool {
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            if (!g_started) return;
        }
        if (!hasSong) {
            loadArtwork(@"");
            [MPNowPlayingInfoCenter defaultCenter].playbackState = MPNowPlayingPlaybackStateStopped;
            [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
            return;
        }
        NSMutableDictionary *info = [NSMutableDictionary dictionary];
        info[MPMediaItemPropertyTitle] = string(env, title);
        info[MPMediaItemPropertyArtist] = string(env, artist);
        info[MPMediaItemPropertyAlbumTitle] = string(env, album);
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @((double)positionMs / 1000.0);
        info[MPMediaItemPropertyPlaybackDuration] = @((double)durationMs / 1000.0);
        info[MPNowPlayingInfoPropertyPlaybackRate] = playing ? @(speed) : @0.0;
        info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = @(speed);
        loadArtwork(string(env, artUrl));
        if (g_artwork) info[MPMediaItemPropertyArtwork] = g_artwork;
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        center.nowPlayingInfo = info;
        center.playbackState = playing ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;
    }
}
