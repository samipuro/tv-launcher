package com.cosyhome.tv;

/**
 * The moon's phase and whether it is above the horizon, offline. Low-precision positions of the moon
 * and the sun (the formulas suncalc uses, from the Astronomical Almanac), good to about a degree:
 * a few minutes on moonrise and moonset, and well inside a day on the phase.
 */
final class Moon {

    private static final double RAD = Math.PI / 180;
    /** Obliquity of the ecliptic. */
    private static final double E = 23.4397 * RAD;
    /** Altitude of the moon's centre at moonrise and moonset, for its parallax, radius and refraction. */
    private static final double HORIZON = 0.133 * RAD;

    private Moon() {
    }

    /**
     * The phase angle in radians: 0 at new moon, pi at full moon, waxing below pi and waning above.
     * It is the moon's ecliptic longitude less the sun's.
     */
    static double phase(long atMillis) {
        double d = days(atMillis);
        double angle = (moonLongitude(d) - sunLongitude(d)) % (2 * Math.PI);
        return angle < 0 ? angle + 2 * Math.PI : angle;
    }

    /** The lit fraction of the disc for a phase angle, 0 to 1. */
    static double illuminated(double phase) {
        return (1 - Math.cos(phase)) / 2;
    }

    /** True when the moon's centre is above the horizon at this place. */
    static boolean up(long atMillis, double latitude, double longitude) {
        double d = days(atMillis);
        double l = moonLongitude(d);
        double b = 5.128 * RAD * Math.sin((93.272 + 13.229350 * d) * RAD);
        double ra = Math.atan2(Math.sin(l) * Math.cos(E) - Math.tan(b) * Math.sin(E), Math.cos(l));
        double dec = Math.asin(Math.sin(b) * Math.cos(E) + Math.cos(b) * Math.sin(E) * Math.sin(l));
        double sidereal = (280.16 + 360.9856235 * d + longitude) * RAD;
        double hour = sidereal - ra;
        double lat = latitude * RAD;
        double altitude = Math.asin(Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(hour));
        return altitude > HORIZON;
    }

    /** Days since J2000.0 (2000-01-01 12:00 TT, taken as UTC: the minute's difference does not matter here). */
    private static double days(long atMillis) {
        return atMillis / 86_400_000.0 + 2440587.5 - 2451545.0;
    }

    /** The moon's geocentric ecliptic longitude, with its largest periodic term. */
    private static double moonLongitude(double d) {
        double mean = (218.316 + 13.176396 * d) * RAD;
        double anomaly = (134.963 + 13.064993 * d) * RAD;
        return mean + 6.289 * RAD * Math.sin(anomaly);
    }

    /** The sun's ecliptic longitude, from the same equation of centre as {@link Sun}. */
    private static double sunLongitude(double d) {
        double m = (357.5291 + 0.98560028 * d) * RAD;
        double c = (1.9148 * Math.sin(m) + 0.02 * Math.sin(2 * m) + 0.0003 * Math.sin(3 * m)) * RAD;
        return m + c + 102.9372 * RAD + Math.PI;
    }
}
