package everykill_legacy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * Tracks XP gained since the session started, and derives an hourly rate from
 * elapsed wall-clock time. A session begins on the first XP drop and ends when
 * the player has been idle longer than the configured timeout.
 */
public class XpSession
{
    private final Map<Skill, Integer> startXp = new EnumMap<>(Skill.class);
    private final Map<Skill, Integer> currentXp = new EnumMap<>(Skill.class);

    private Instant startedAt;
    private Instant lastGainAt;

    /** Label describing what was being trained, e.g. a slayer task name. */
    private String label = "unlabelled";

    public boolean isActive()
    {
        return startedAt != null;
    }

    public String getLabel()
    {
        return label;
    }

    public void setLabel(String label)
    {
        this.label = label == null || label.isEmpty() ? "unlabelled" : label;
    }

    /**
     * Seed the baseline for a skill without counting it as a gain. Called when
     * the client first reports XP after login, so the initial StatChanged burst
     * doesn't register as a huge fake gain.
     */
    public void seed(Skill skill, int xp)
    {
        startXp.put(skill, xp);
        currentXp.put(skill, xp);
    }

    public void record(Skill skill, int xp, Instant now)
    {
        if (!startXp.containsKey(skill))
        {
            seed(skill, xp);
            return;
        }

        int previous = currentXp.getOrDefault(skill, xp);
        if (xp <= previous)
        {
            // No gain, or a rollback after a hop. Nothing to record.
            currentXp.put(skill, xp);
            return;
        }

        currentXp.put(skill, xp);

        if (startedAt == null)
        {
            startedAt = now;
        }
        lastGainAt = now;
    }

    public int gained(Skill skill)
    {
        Integer start = startXp.get(skill);
        Integer current = currentXp.get(skill);
        if (start == null || current == null)
        {
            return 0;
        }
        return Math.max(0, current - start);
    }

    public int totalGained()
    {
        int total = 0;
        for (Skill skill : startXp.keySet())
        {
            total += gained(skill);
        }
        return total;
    }

    public Duration elapsed(Instant now)
    {
        if (startedAt == null)
        {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, now);
    }

    /**
     * Hourly rate for a skill. Returns 0 until at least a minute has elapsed,
     * because rates extrapolated from a few seconds are meaningless.
     */
    public long ratePerHour(Skill skill, Instant now)
    {
        Duration elapsed = elapsed(now);
        if (elapsed.getSeconds() < 60)
        {
            return 0;
        }
        return Math.round(gained(skill) * 3600.0 / elapsed.getSeconds());
    }

    public long totalRatePerHour(Instant now)
    {
        Duration elapsed = elapsed(now);
        if (elapsed.getSeconds() < 60)
        {
            return 0;
        }
        return Math.round(totalGained() * 3600.0 / elapsed.getSeconds());
    }

    public boolean isIdle(Instant now, int idleMinutes)
    {
        if (idleMinutes <= 0 || lastGainAt == null)
        {
            return false;
        }
        return Duration.between(lastGainAt, now).toMinutes() >= idleMinutes;
    }

    /** Skills that have actually gained XP this session. */
    public Map<Skill, Integer> gains()
    {
        Map<Skill, Integer> result = new EnumMap<>(Skill.class);
        for (Skill skill : startXp.keySet())
        {
            int gain = gained(skill);
            if (gain > 0)
            {
                result.put(skill, gain);
            }
        }
        return result;
    }

    /**
     * Restart the session, keeping current XP values as the new baseline so the
     * next session measures from here rather than from login.
     */
    public void reset()
    {
        startXp.clear();
        startXp.putAll(currentXp);
        startedAt = null;
        lastGainAt = null;
        label = "unlabelled";
    }

    public Instant getStartedAt()
    {
        return startedAt;
    }
}
