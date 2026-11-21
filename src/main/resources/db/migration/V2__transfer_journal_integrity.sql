-- Keep the already applied V1 intact; strengthen the invariant in a new migration.
CREATE OR REPLACE FUNCTION check_journal_balance() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal_key UUID;
    header journals%ROWTYPE;
    movement transfers%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME IN ('journals', 'transfers') THEN journal_key := NEW.id;
    ELSE journal_key := NEW.journal_id;
    END IF;
    SELECT * INTO header FROM journals WHERE id = journal_key;
    IF (SELECT count(*) FROM ledger_entries WHERE journal_id = journal_key) <> 2
       OR (SELECT coalesce(sum(signed_amount), 0) FROM ledger_entries WHERE journal_id = journal_key) <> 0 THEN
        RAISE EXCEPTION 'A journal must contain exactly two balanced postings' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM ledger_entries e JOIN accounts a ON a.id = e.account_id
               WHERE e.journal_id = journal_key AND a.currency <> header.currency) THEN
        RAISE EXCEPTION 'Posting currency must match journal currency' USING ERRCODE = '23514';
    END IF;
    IF header.kind = 'TRANSFER' THEN
        SELECT * INTO movement FROM transfers WHERE id = journal_key;
        IF NOT FOUND OR movement.currency <> header.currency
           OR NOT EXISTS (SELECT 1 FROM ledger_entries WHERE journal_id = journal_key
                          AND account_id = movement.source_account_id AND signed_amount = -movement.amount)
           OR NOT EXISTS (SELECT 1 FROM ledger_entries WHERE journal_id = journal_key
                          AND account_id = movement.destination_account_id AND signed_amount = movement.amount) THEN
            RAISE EXCEPTION 'Transfer must match its journal postings' USING ERRCODE = '23514';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM transfers WHERE id = journal_key) THEN
        RAISE EXCEPTION 'A transfer must use a TRANSFER journal' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER transfer_balanced AFTER INSERT ON transfers
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal_balance();
