#pragma version(1)
#pragma rs_fp_relaxed
#pragma rs java_package_name(com.xuggle)

rs_allocation output_allocation;
int height;
int width;

void RS_KERNEL rotate270(uchar4 in, int x, int y) {
  rsSetElementAt_uchar4(output_allocation, in, (height - 1) - y, (width - 1) - x);

}

void RS_KERNEL rotate180(uchar4 in, int x, int y) {
  rsSetElementAt_uchar4(output_allocation, in, (width - 1) - x, (height - 1) - y);
}

void RS_KERNEL rotate90(uchar4 in, int x, int y) {
  rsSetElementAt_uchar4(output_allocation, in, (height - 1) - y, x);
}