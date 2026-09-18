# INC-2026-09-11 Resolution & Incident Report

## 1. Reproduction
- **Test:** `AmountsTest.reproducesIncidentInc20260911WaterCanIntegerAmount()` in `src/test/java/in/simplifymoney/ledgersync/AmountsTest.java`.
- **Input SMS:** `"Rs.5 debited from HDFC Bank a/c **4821 on 27-06-26 to VPA UPI/WATER CAN. Avl Bal: Rs.92,213.10"`
- **Behavior before fix:** `Amounts.first` skipped `"Rs.5"` because it lacked `.XX` paise, matching `"Avl Bal: Rs.92,213.10"` instead, producing a bogus transaction of ₹92,213.10.
- **Behavior after fix:** Correctly extracts `5.00` and assigns `92213.10` to `statedBalance`.

## 2. Root Cause
- **File & Line:** `src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java`, Line 18.
- **Defective Regex:**
  ```java
  Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})", Pattern.CASE_INSENSITIVE);
  ```
  The regex demanded an explicit decimal point with two digits (`\\.[0-9]{2}`). Integer amounts like `Rs.5` failed the match completely, causing `Amounts.first()` to iterate forward and latch onto the first token that did have decimal paise: the customer's available balance (`Avl Bal: Rs.92,213.10`).

## 3. Blast Radius
- **Affected Messages in `fixtures/corpus-a.jsonl`:** Exactly **44 messages**.
- **Deciding Rule:** Any transaction notification where the transaction amount is an integer rupee value (e.g., `Rs 5`, `Rs. 500`, `INR 1200`) without explicit decimal paise, followed by an available balance or credit limit that includes decimal paise (e.g., `Avl Bal: Rs.92,213.10`). In all such messages, the spend was recorded as the user's total bank balance rather than the actual transaction amount.

## 4. The Fix
- Changed regex in `Amounts.java` to make the decimal paise optional:
  ```java
  Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{2})?)", Pattern.CASE_INSENSITIVE);
  ```
- All numbers are parsed and normalized via `new BigDecimal(...).setScale(2, RoundingMode.UNNECESSARY)`.

---

## 5. Five-Line Incident Channel Note

1. **What broke:** `Amounts.java:18` required decimal paise (`\.[0-9]{2}`), skipping integer amounts like `Rs.5` and incorrectly extracting the subsequent `Avl Bal: Rs.92,213.10` as the transaction amount.
2. **How found:** Reproduced via unit test `reproducesIncidentInc20260911WaterCanIntegerAmount` matching the customer's raw SMS and trace `m-legacy-0041`.
3. **Blast radius:** 44 messages across `corpus-a.jsonl` where integer rupee debits were recorded as the customer's total bank balance.
4. **The fix:** Updated regex to `(?:Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{2})?)` to match both integer and decimal currency amounts with zero-padded 2-decimal scale.
5. **Prevention:** Added regression test cases in `AmountsTest` covering integer INR, comma-delimited amounts, and boundary assertions preventing fallback to balance tokens.
