package me.blvckbytes.minimalparser.parser;

import lombok.AllArgsConstructor;
import me.blvckbytes.minimalparser.IValueInterpreter;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.function.BiFunction;

@AllArgsConstructor
public enum NumberCompare {

  GREATER_THAN((a, b) -> a.compareTo(b) > 0),
  GREATER_THAN_OR_EQUAL((a, b) -> a.compareTo(b) >= 0),
  LESS_THAN((a, b) -> a.compareTo(b) < 0),
  LESS_THAN_OR_EQUAL((a, b) -> a.compareTo(b) <= 0),
  EQUAL((a, b) -> a.compareTo(b) == 0)
  ;

  private final BiFunction<BigDecimal, BigDecimal, Boolean> function;

  public boolean apply(@Nullable Object a, @Nullable Object b, IValueInterpreter interpreter) {
    BigDecimal numberA = interpreter.tryParseNumber(a).orElse(null);
    BigDecimal numberB = interpreter.tryParseNumber(b).orElse(null);

    if (numberA == null)
      numberA = new BigDecimal(0);

    if (numberB == null)
      numberB = new BigDecimal(0);

    return function.apply(numberA, numberB);
  }
}