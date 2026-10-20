package net.tfminecraft.geigercounters.utils;

// ====================================
// How close a reading is, from 0 at <far> to 1 at <near> and closer.
//
// The scale is logarithmic: every halving of the distance adds the same
// amount. On a straight line a 2,500-block range leaves the last few hundred
// blocks reading as all but full, so nothing changes where players need it.
// ====================================
public final class Closeness {

    private Closeness() {
    }

    public static double logarithmic(double distance, double near, double far) {
        if (distance <= near) {
            return 1.0;
        }
        if (distance >= far) {
            return 0.0;
        }
        return 1.0 - Math.log(distance / near) / Math.log(far / near);
    }
}
