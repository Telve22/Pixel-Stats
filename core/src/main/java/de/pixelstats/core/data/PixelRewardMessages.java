package de.pixelstats.core.data;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.labymod.api.client.chat.ChatMessage;
import net.labymod.api.client.chat.ChatTrustLevel;

public final class PixelRewardMessages {

  private static final Pattern ATTRIBUTED_TRANSFER =
      Pattern.compile("(?i)\\b[A-Za-z0-9_]{1,16}\\s*(?:[✦✧➜→]|->)\\s*[A-Za-z0-9_]{1,16}\\s*[»›:]");

  private PixelRewardMessages() {}

  public static Optional<BigDecimal> amount(ChatMessage message) {
    if (message == null
        || message.trustLevel() != ChatTrustLevel.SYSTEM
        || message.getSenderProfile() != null) {
      return Optional.empty();
    }
    UUID sender = message.getSenderUniqueId();
    if (sender != null
        && (sender.getMostSignificantBits() != 0 || sender.getLeastSignificantBits() != 0)) {
      return Optional.empty();
    }
    String text = PixelTextParser.clean(message.getOriginalPlainText());
    // SYSTEM is also used by local addons. Reject explicit sender/recipient reports
    // and our own debug echoes; every remaining claim still needs scoreboard backing.
    int credit = text.indexOf('+');
    if (text.startsWith("[Pixel Stats]")
        || (credit >= 0 && ATTRIBUTED_TRANSFER.matcher(text.substring(0, credit)).find())) {
      return Optional.empty();
    }
    return PixelTextParser.reward(text);
  }
}
