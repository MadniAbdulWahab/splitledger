package dev.splitledger.bankimport;

import org.springframework.data.jpa.repository.JpaRepository;

interface BankImportRepository extends JpaRepository<BankImport, Long> {
}
