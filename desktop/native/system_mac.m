#import <CoreWLAN/CoreWLAN.h>
#import <Foundation/Foundation.h>
#import <SystemConfiguration/SystemConfiguration.h>
#import <ImageIO/ImageIO.h>
#import <IOKit/ps/IOPSKeys.h>
#import <IOKit/ps/IOPowerSources.h>
#import <QuickLookThumbnailing/QuickLookThumbnailing.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* The internal battery's charge, 0 to 1, and whether it is charging; 0 when there is one, -1 when there is none. */
int32_t halone_battery(double *level, int32_t *charging) {
    int32_t found = -1;
    CFTypeRef info = IOPSCopyPowerSourcesInfo();
    if (!info) return -1;
    CFArrayRef sources = IOPSCopyPowerSourcesList(info);
    for (CFIndex i = 0; sources && i < CFArrayGetCount(sources); i++) {
        NSDictionary *source = (__bridge NSDictionary *)IOPSGetPowerSourceDescription(info, CFArrayGetValueAtIndex(sources, i));
        if (![source[@kIOPSTypeKey] isEqual:@kIOPSInternalBatteryType]) continue;
        double current = [source[@kIOPSCurrentCapacityKey] doubleValue];
        double max = [source[@kIOPSMaxCapacityKey] doubleValue];
        if (max <= 0) continue;
        *level = current / max;
        *charging = [source[@kIOPSIsChargingKey] boolValue] || [source[@kIOPSIsChargedKey] boolValue] ? 1 : 0;
        found = 0;
        break;
    }
    if (sources) CFRelease(sources);
    CFRelease(info);
    return found;
}

/*
 * A JPEG of the file's thumbnail as Finder makes it, through QuickLook and its
 * cache, fitting [sizePx] square: a picture turned upright, a frame of a video,
 * a song's cover art. Only a real thumbnail, never a file icon. 0 with
 * [out] (for halone_free) and [length] set, or -1 when there is none.
 */
int32_t halone_thumbnail(const char *path, int32_t sizePx, double quality, uint8_t **out, int32_t *length) {
    @autoreleasepool {
        NSURL *url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:path]];
        QLThumbnailGenerationRequest *request =
            [[QLThumbnailGenerationRequest alloc] initWithFileAtURL:url
                                                               size:CGSizeMake(sizePx, sizePx)
                                                              scale:1.0
                                                representationTypes:QLThumbnailGenerationRequestRepresentationTypeThumbnail];
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        __block CGImageRef image = NULL;
        [[QLThumbnailGenerator sharedGenerator]
            generateBestRepresentationForRequest:request
                               completionHandler:^(QLThumbnailRepresentation *thumbnail, NSError *error) {
                                   if (thumbnail && thumbnail.type == QLThumbnailRepresentationTypeThumbnail)
                                       image = CGImageRetain(thumbnail.CGImage);
                                   dispatch_semaphore_signal(done);
                               }];
        if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) != 0) {
            [[QLThumbnailGenerator sharedGenerator] cancelRequest:request];
            return -1;
        }
        if (!image) return -1;
        NSMutableData *data = [NSMutableData data];
        CGImageDestinationRef destination =
            CGImageDestinationCreateWithData((__bridge CFMutableDataRef)data, (__bridge CFStringRef)UTTypeJPEG.identifier, 1, NULL);
        NSDictionary *options = @{(__bridge NSString *)kCGImageDestinationLossyCompressionQuality: @(quality)};
        CGImageDestinationAddImage(destination, image, (__bridge CFDictionaryRef)options);
        bool written = CGImageDestinationFinalize(destination);
        CFRelease(destination);
        CGImageRelease(image);
        if (!written || data.length == 0) return -1;
        *out = malloc(data.length);
        memcpy(*out, data.bytes, data.length);
        *length = (int32_t)data.length;
        return 0;
    }
}

/*
 * How this Mac is online: [kind] 0 for not at all, 1 for Wi-Fi with [bars]
 * of 4 from its signal, 2 for anything else, such as Ethernet. The route the
 * system uses decides; Wi-Fi counts while associated even under a VPN.
 */
void halone_network(int32_t *kind, int32_t *bars) {
    *kind = 0;
    *bars = 0;
    SCDynamicStoreRef store = SCDynamicStoreCreate(NULL, CFSTR("halone"), NULL, NULL);
    if (!store) return;
    NSDictionary *global = CFBridgingRelease(SCDynamicStoreCopyValue(store, CFSTR("State:/Network/Global/IPv4")));
    CFRelease(store);
    if (!global[@"PrimaryInterface"]) return;
    CWInterface *wifi = [CWWiFiClient sharedWiFiClient].interface;
    NSInteger rssi = wifi.powerOn ? wifi.rssiValue : 0;
    if (rssi == 0) {
        *kind = 2;
        return;
    }
    *kind = 1;
    *bars = rssi >= -55 ? 4 : rssi >= -66 ? 3 : rssi >= -77 ? 2 : rssi >= -88 ? 1 : 0;
}

void halone_free(void *pointer) { free(pointer); }
