CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(200) NOT NULL CHECK (length(trim(owner_id)) > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('PEN', 'USD')),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('CUSTOMER', 'EQUITY')),
    balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (kind = 'EQUITY' OR balance >= 0)
);
CREATE INDEX accounts_owner_idx ON accounts (owner_id, id);

CREATE TABLE journals (
    id UUID PRIMARY KEY,
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('PEN', 'USD')),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('OPENING', 'TRANSFER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transfers (
    id UUID PRIMARY KEY REFERENCES journals(id) DEFERRABLE INITIALLY DEFERRED,
    principal_id VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]+$'),
    source_account_id UUID NOT NULL REFERENCES accounts(id),
    destination_account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('PEN', 'USD')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (principal_id, idempotency_key),
    CHECK (source_account_id <> destination_account_id)
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL REFERENCES journals(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    signed_amount NUMERIC(19, 2) NOT NULL CHECK (signed_amount <> 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (journal_id, account_id)
);
CREATE INDEX ledger_account_date_idx ON ledger_entries (account_id, created_at DESC, id DESC);

-- Evaluate at COMMIT, once all parts of the atomic operation exist.
CREATE FUNCTION check_journal_balance() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal_key UUID;
    header journals%ROWTYPE;
    movement transfers%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'journals' THEN journal_key := NEW.id;
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
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER journal_balanced AFTER INSERT ON journals
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal_balance();
CREATE CONSTRAINT TRIGGER posting_balanced AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal_balance();

CREATE FUNCTION check_account_balance() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    account_key UUID;
BEGIN
    IF TG_TABLE_NAME = 'accounts' THEN account_key := NEW.id;
    ELSE account_key := NEW.account_id;
    END IF;
    IF (SELECT balance FROM accounts WHERE id = account_key)
       <> (SELECT coalesce(sum(signed_amount), 0) FROM ledger_entries WHERE account_id = account_key) THEN
        RAISE EXCEPTION 'Account balance must equal its ledger postings' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER account_reconciled AFTER INSERT OR UPDATE ON accounts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_account_balance();
CREATE CONSTRAINT TRIGGER posting_reconciled AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_account_balance();

CREATE FUNCTION reject_ledger_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Posted financial records are immutable; use a new compensating journal' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER immutable_entries BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER immutable_journals BEFORE UPDATE OR DELETE ON journals FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER immutable_transfers BEFORE UPDATE OR DELETE ON transfers FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
