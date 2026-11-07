// Host contract for raw_inset_crop.h: where LibRaw's raw_inset_crops[0] lands in the processed
// bitmap. GPLv3.
//
// The flip cases are pinned to what Jing Yu measured on a Sony A1 II through rawpy (spektrafilm
// fork arigatou1985, 7e35f37): with the usable box at the top-left of the sensor frame, the
// masked band comes out at
//   flip 0 -> bottom rows, right cols      flip 3 -> top rows, left cols
//   flip 5 -> top rows, right cols         flip 6 -> bottom rows, left cols
#include "raw_inset_crop.h"

#include <cstdio>

namespace {

using spectrafilm::rawcrop::InsetGeometry;
using spectrafilm::rawcrop::OutputCrop;
using spectrafilm::rawcrop::insetCropForBitmap;

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

bool is(const OutputCrop& c, int x, int y, int w, int h) {
    return c.active && c.x == x && c.y == y && c.width == w && c.height == h;
}

// A1 II-like frame: visible 8672 x 5792 (sensor orientation), usable 8640 x 5760 at the
// visible origin, so a 32-col band on the right and a 32-row band at the bottom.
InsetGeometry a1ii(int flip) {
    InsetGeometry g;
    g.width = 8672;
    g.height = 5792;
    g.leftMargin = 0;
    g.topMargin = 0;
    g.rawWidth = 8672;
    g.rawHeight = 5792;
    g.flip = flip;
    g.cropLeft = 0;
    g.cropTop = 0;
    g.cropWidth = 8640;
    g.cropHeight = 5760;
    return g;
}

}  // namespace

int main() {
    // Landscape: bitmap is the visible frame.
    check(is(insetCropForBitmap(a1ii(0), 8672, 5792), 0, 0, 8640, 5760),
          "flip 0: band at bottom rows and right cols is dropped");
    check(is(insetCropForBitmap(a1ii(3), 8672, 5792), 32, 32, 8640, 5760),
          "flip 3: band at top rows and left cols is dropped");
    // Portrait: LibRaw transposes, the bitmap is 5792 wide.
    check(is(insetCropForBitmap(a1ii(5), 5792, 8672), 0, 32, 5760, 8640),
          "flip 5: band at top rows and right cols is dropped");
    check(is(insetCropForBitmap(a1ii(6), 5792, 8672), 32, 0, 5760, 8640),
          "flip 6: band at bottom rows and left cols is dropped");

    // half_size halves the bitmap; the box shrinks with it, rounded inward.
    check(is(insetCropForBitmap(a1ii(0), 4336, 2896), 0, 0, 4320, 2880),
          "half size scales the box");
    {
        InsetGeometry g = a1ii(0);
        g.cropLeft = 3;  // odd origin: 1.5 px at half size rounds in to 2
        g.cropWidth = 8641;
        check(is(insetCropForBitmap(g, 4336, 2896), 2, 0, 4320, 2880),
              "a fractional edge rounds inward");
    }

    // Margins: the inset box is in raw-frame coordinates, the bitmap starts at the margins.
    {
        InsetGeometry g = a1ii(0);
        g.leftMargin = 10;
        g.topMargin = 4;
        g.rawWidth = 8700;
        g.rawHeight = 5800;
        g.cropLeft = 10 + 16;
        g.cropTop = 4 + 8;
        check(is(insetCropForBitmap(g, 8672, 5792), 16, 8, 8640, 5760),
              "box is placed relative to the visible origin");
    }

    // Every guard leaves the bitmap whole.
    check(!insetCropForBitmap(InsetGeometry{}, 100, 100).active, "no reported box: no crop");
    {
        InsetGeometry g = a1ii(0);
        g.cropWidth = 8672;
        g.cropHeight = 5792;
        check(!insetCropForBitmap(g, 8672, 5792).active, "box equal to the frame: no crop");
    }
    {
        InsetGeometry g = a1ii(0);
        g.cropLeft = 64;  // reaches past the visible frame
        check(!insetCropForBitmap(g, 8672, 5792).active, "box past the frame: no crop");
    }
    {
        InsetGeometry g = a1ii(0);
        g.cropWidth = 4000;  // keeps under 55 % of the width
        check(!insetCropForBitmap(g, 8672, 5792).active, "box too small: no crop");
    }
    {
        InsetGeometry g = a1ii(0);
        g.fujiRotated = true;
        check(!insetCropForBitmap(g, 8672, 5792).active, "Fuji 45-degree layout: no crop");
    }
    {
        InsetGeometry g = a1ii(0);
        g.cropLeft = 0xffff;
        check(!insetCropForBitmap(g, 8672, 5792).active, "0xffff origin means unreported");
    }
    {
        InsetGeometry g = a1ii(0);
        g.rawWidth = 8000;  // box overruns the raw frame
        check(!insetCropForBitmap(g, 8672, 5792).active, "box past the raw frame: no crop");
    }

    std::printf("raw-inset-crop: %d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
