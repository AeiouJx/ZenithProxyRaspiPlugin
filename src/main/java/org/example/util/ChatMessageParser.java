package org.example.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatMessageParser {
    private ChatMessageParser() { }

    public record PrivateMessage(String sender, String receiver, String message) { }

    public record PublicMessage(String sender, String message) { }

    public record AchievementMessage(String player, String achievement) { }

    private static final Pattern PRIVATE_PATTERN = Pattern.compile(
        "^📨\\s*(.+?)\\s*[➡→]\\s*(.+)$"
    );

    private static final Pattern PUBLIC_PATTERN = Pattern.compile(
        "^«(.+?)»\\s*(.*)$"
    );

    private static final Pattern[] ACHIEVEMENT_PATTERNS = {
        Pattern.compile("^(.*) has made the advancement: (.*)$"),
        Pattern.compile("^(.*) has completed the challenge: (.*)$"),
        Pattern.compile("^(.*) has reached the goal: (.*)$"),
    };

    public static Optional<PrivateMessage> parsePrivate(final String raw) {
        if (raw == null) return Optional.empty();
        final Matcher matcher = PRIVATE_PATTERN.matcher(raw);
        if (!matcher.matches()) return Optional.empty();
        final String sender = matcher.group(1).trim();
        final String rest = matcher.group(2).trim();
        if ("You".equals(sender)) {
            final int space = rest.indexOf(' ');
            if (space > 0) {
                return Optional.of(new PrivateMessage(sender, rest.substring(0, space), rest.substring(space + 1).trim()));
            }
            return Optional.of(new PrivateMessage(sender, rest, ""));
        }
        return Optional.of(new PrivateMessage(sender, "", rest));
    }

    public static Optional<PublicMessage> parsePublic(final String raw) {
        if (raw == null) return Optional.empty();
        final Matcher matcher = PUBLIC_PATTERN.matcher(raw);
        if (!matcher.matches()) return Optional.empty();
        return Optional.of(new PublicMessage(matcher.group(1).trim(), matcher.group(2).trim()));
    }

    public static Optional<AchievementMessage> parseAchievement(final String raw) {
        if (raw == null) return Optional.empty();
        for (final Pattern pattern : ACHIEVEMENT_PATTERNS) {
            final Matcher matcher = pattern.matcher(raw);
            if (matcher.matches()) {
                return Optional.of(new AchievementMessage(matcher.group(1), matcher.group(2)));
            }
        }
        return Optional.empty();
    }
}