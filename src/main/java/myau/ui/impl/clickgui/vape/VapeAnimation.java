package myau.ui.impl.clickgui.vape;

import java.awt.*;

/**
 * Vape's linear, time-based animation. Progress runs 0..100 over the duration; playing it
 * backwards counts down from 100. {@link #toggle()} reverses mid-way, keeping the position,
 * which is how hover effects fade in and out.
 */
class VapeAnimation {
    private final long duration;
    private long start = Long.MAX_VALUE;
    private boolean reversed;

    VapeAnimation(double seconds) {
        this.duration = (long) (seconds * 1000.0);
    }

    /** 0..100. */
    double progress() {
        long elapsed = System.currentTimeMillis() - this.start;
        double value = this.duration <= 0L ? (elapsed >= 0L ? 100.0 : 0.0)
                : Math.min((double) elapsed / (double) this.duration * 100.0, 100.0);
        if (value < 0.0) {
            value = 0.0;
        }
        return this.reversed ? 100.0 - value : value;
    }

    /** Plays towards the end, from the start. */
    void forward() {
        this.start = System.currentTimeMillis();
        this.reversed = false;
    }

    /** Plays towards the start, from the end. */
    void backward() {
        this.start = System.currentTimeMillis();
        this.reversed = true;
    }

    void snapToEnd() {
        this.start = 0L;
        this.reversed = false;
    }

    void snapToStart() {
        this.start = 0L;
        this.reversed = true;
    }

    /** Heading towards (or sitting at) the end. */
    boolean isOn() {
        return !this.reversed && this.progress() != 0.0;
    }

    /** Reverses direction from wherever it is. */
    void toggle() {
        double value = this.progress();
        if (value == 0.0) {
            this.forward();
            return;
        }
        if (value == 100.0) {
            this.backward();
            return;
        }
        long now = System.currentTimeMillis();
        long remaining = this.duration - (now - this.start);
        this.start = now - remaining;
        this.reversed = !this.reversed;
    }

    /** Starts towards the requested end unless already there or heading there. */
    void set(boolean on) {
        double value = this.progress();
        if (on) {
            if (value == 0.0) {
                this.forward();
            } else if (this.reversed && value != 100.0) {
                this.toggle();
            }
        } else if (value == 100.0) {
            this.backward();
        } else if (!this.reversed && value != 0.0) {
            this.toggle();
        }
    }

    static final class Value extends VapeAnimation {
        private final double from;
        private final double to;

        Value(double seconds, double from, double to) {
            super(seconds);
            this.from = from;
            this.to = to;
        }

        double value() {
            double progress = this.progress();
            if (progress == 0.0) {
                return this.from;
            }
            if (progress == 100.0) {
                return this.to;
            }
            return this.from + progress * (this.to - this.from) / 100.0;
        }

        double end() {
            return this.to;
        }
    }

    static final class Colour extends VapeAnimation {
        private Color from;
        private Color to;

        Colour(double seconds, Color from, Color to) {
            super(seconds);
            this.from = from;
            this.to = to;
        }

        void setTo(Color to) {
            this.to = to;
        }

        Color color() {
            double progress = this.progress();
            if (progress == 0.0) {
                return this.from;
            }
            if (progress == 100.0) {
                return this.to;
            }
            return new Color(
                    this.from.getRed() + (int) (progress * (this.to.getRed() - this.from.getRed()) / 100.0),
                    this.from.getGreen() + (int) (progress * (this.to.getGreen() - this.from.getGreen()) / 100.0),
                    this.from.getBlue() + (int) (progress * (this.to.getBlue() - this.from.getBlue()) / 100.0),
                    this.from.getAlpha() + (int) (progress * (this.to.getAlpha() - this.from.getAlpha()) / 100.0));
        }
    }
}
