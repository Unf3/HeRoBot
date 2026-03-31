package hero.bane.herobot.bot.pathing;

public class MoveResult {
    public int x;
    public int y;
    public int z;
    public double cost;

    public static final double COST_INF = Double.POSITIVE_INFINITY;

    public MoveResult() {
        reset();
    }

    public void reset() {
        x = 0;
        y = 0;
        z = 0;
        cost = COST_INF;
    }
}
