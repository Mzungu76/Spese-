package it.spese.data.mapper

import it.spese.core.common.Money
import it.spese.core.database.entity.*
import it.spese.core.model.*
import it.spese.domain.repository.NewTransaction
import java.time.LocalDate

private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E = runCatching { enumValueOf<E>(name) }.getOrDefault(default)

fun AccountEntity.toDomain() = Account(id, name, enumOr(type, AccountType.CONTO), Money.fromCents(openingCents), archived)
fun Account.toEntity() = AccountEntity(id, name.trim(), type.name, Money.toCents(openingBalance), archived)

fun CategoryEntity.toDomain() = Category(
    id, name, TxKind.fromKey(kind) ?: TxKind.SPESA, flexible, color, budgetCents?.let { Money.fromCents(it) }, active, sortOrder, systemKey, icon,
)

fun RuleEntity.toDomain() = Rule(id, keyword, categoryId, AmountSign.fromKey(sign), Money.fromCents(minCents), Money.fromCents(maxCents), priority, active)

fun TransactionEntity.toDomain() = Transaction(
    id, externalId, accountId, LocalDate.ofEpochDay(bookingDate), LocalDate.ofEpochDay(valueDate), description, Money.fromCents(amountCents),
    TxKind.fromKey(kind) ?: TxKind.SPESA, categoryId, manualCategory, ruleId, merchantOverride, notes, enumOr(origin, TxOrigin.MANUAL), documentId, createdAt,
)

fun NewTransaction.toEntity(now: Long) = TransactionEntity(
    externalId = externalId, dedupKey = dedupKey, accountId = accountId, bookingDate = bookingDate.toEpochDay(), valueDate = valueDate.toEpochDay(),
    description = description, amountCents = Money.toCents(amount), kind = kind.key, categoryId = categoryId, manualCategory = manualCategory,
    manualUpdatedAt = if (manualCategory) now else 0, ruleId = ruleId, merchantOverride = merchantOverride, notes = notes, origin = origin.name,
    documentId = documentId, importBatchId = importBatchId, createdAt = now,
)

fun DueEntity.toDomain() = Due(id, legacyId, name, Money.fromCents(amountCents), LocalDate.ofEpochDay(nextDate), DueFrequency.fromKey(frequency))

fun DocumentEntity.toDomain() = DocumentRef(id, fileName, mimeType, sizeBytes, createdAt, ocrText)
