package pzopt;

/** Picker RGB decoding without a rendering context. */
public final class OccludedOutlineColourTest {
  public static void main(String[] args) {
    check("FFC740", 0xFFC740);
    check("#12abEF", 0x12ABEF);
    check(" 12abef ", 0x12ABEF);
    check("000000", 0);
    check("FFFFFF", 0xFFFFFF);
    for (String invalid : new String[] {null, "", "12345", "1234567", "GGGGGG", "-12345", "red"}) {
      check(invalid, 0xFFC740);
    }
    // the contour shader hard-codes the stencil codes: S | V = 0x60, brightness 0xF, all below the game's 0x80 bit
    if ((OccludedOutline.S | OccludedOutline.V) != 0x60 || OccludedOutline.B != 0x0F
        || ((OccludedOutline.S | OccludedOutline.V | OccludedOutline.B) & 0x80) != 0
        || (OccludedOutline.S & OccludedOutline.V) != 0 || ((OccludedOutline.S | OccludedOutline.V) & OccludedOutline.B) != 0) {
      throw new AssertionError("stencil code layout changed: update COMPOSITE_FRAG");
    }
    System.out.println("OccludedOutlineColourTest passed");
  }

  private static void check(String text, int expected) {
    int actual = OccludedOutline.parseColour(text);
    if (actual != expected) throw new AssertionError(text + ": " + Integer.toHexString(actual));
  }
}
