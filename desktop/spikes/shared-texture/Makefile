CFLAGS = -O2 -Wall -Wextra -Wno-unused-parameter -fobjc-arc -mmacosx-version-min=14.0
OUT = build

all: $(OUT)/consumer $(OUT)/producer

$(OUT)/consumer: consumer.m protocol.h | $(OUT)
	clang $(CFLAGS) -o $@ consumer.m -framework Cocoa -framework OpenGL -framework IOSurface -framework QuartzCore

$(OUT)/producer: producer.m protocol.h | $(OUT)
	clang $(CFLAGS) -o $@ producer.m -framework Foundation -framework Metal -framework IOSurface

$(OUT):
	mkdir -p $@

run: all
	$(OUT)/consumer $(ARGS)

clean:
	rm -rf $(OUT)

.PHONY: all run clean
