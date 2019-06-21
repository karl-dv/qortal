package org.qora.repository.hsqldb.transaction;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.qora.data.transaction.BuyNameTransactionData;
import org.qora.data.transaction.BaseTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.DataException;
import org.qora.repository.hsqldb.HSQLDBRepository;
import org.qora.repository.hsqldb.HSQLDBSaver;

public class HSQLDBBuyNameTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBBuyNameTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT name, amount, seller, name_reference FROM BuyNameTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String name = resultSet.getString(1);
			BigDecimal amount = resultSet.getBigDecimal(2);
			String seller = resultSet.getString(3);
			byte[] nameReference = resultSet.getBytes(4);

			return new BuyNameTransactionData(baseTransactionData, name, amount, seller, nameReference);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch buy name transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		BuyNameTransactionData buyNameTransactionData = (BuyNameTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("BuyNameTransactions");

		saveHelper.bind("signature", buyNameTransactionData.getSignature()).bind("buyer", buyNameTransactionData.getBuyerPublicKey())
				.bind("name", buyNameTransactionData.getName()).bind("amount", buyNameTransactionData.getAmount())
				.bind("seller", buyNameTransactionData.getSeller()).bind("name_reference", buyNameTransactionData.getNameReference());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save buy name transaction into repository", e);
		}
	}

}
