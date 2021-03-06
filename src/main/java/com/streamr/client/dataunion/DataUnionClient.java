package com.streamr.client.dataunion;

import static com.streamr.client.dataunion.Web3jUtils.fromBytes65;
import static com.streamr.client.dataunion.Web3jUtils.toWei;
import static com.streamr.client.dataunion.Web3jUtils.waitForCondition;
import static com.streamr.client.dataunion.Web3jUtils.waitForErc20BalanceChange;
import static com.streamr.client.dataunion.Web3jUtils.waitForTx;
import static org.web3j.tx.Contract.staticExtractEventParameters;

import com.streamr.client.dataunion.Web3jUtils.Condition;
import com.streamr.client.dataunion.contracts.DataUnionFactoryMainnet;
import com.streamr.client.dataunion.contracts.DataUnionFactorySidechain;
import com.streamr.client.dataunion.contracts.DataUnionMainnet;
import com.streamr.client.dataunion.contracts.DataUnionSidechain;
import com.streamr.client.dataunion.contracts.ForeignAMB;
import com.streamr.client.dataunion.contracts.HomeAMB;
import com.streamr.client.dataunion.contracts.IERC20;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventValues;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

/**
 * Client for DU2. functions include 1. interact with DataUnionFactories: create DataUnion, load
 * DataUnion. 2. perform bridge functions: portTxsToMainnet 3. util functions: waitForTx
 *
 * <p>use DataUnion to perform functions on a particular DU.
 */
public final class DataUnionClient {
  private static final Logger log = LoggerFactory.getLogger(DataUnionClient.class);

  private final Web3j mainnet;
  private final Web3j sidechain;
  private final Credentials mainnetCred;
  private final Credentials sidechainCred;
  private final String sidechainFactory;
  private final String mainnetFactory;
  private final long bridgePollInterval = 10000;
  private final long bridgePollTimeout = 600000;
  private final EstimatedGasProvider mainnetGasProvider;
  private final EstimatedGasProvider sidechainGasProvider;

  public DataUnionClient(
      final String mainnetUrl,
      final String mainnetFactory,
      final String mainnetAdminPrvKey,
      final String sidechainUrl,
      final String sidechainFactory,
      final String sidechainAdminPrvKey) {
    this.mainnet = Web3j.build(new HttpService(mainnetUrl));
    this.mainnetFactory = mainnetFactory;
    this.mainnetCred = Credentials.create(mainnetAdminPrvKey);
    this.sidechain = Web3j.build(new HttpService(sidechainUrl));
    this.sidechainFactory = sidechainFactory;
    this.sidechainCred = Credentials.create(sidechainAdminPrvKey);
    mainnetGasProvider = new EstimatedGasProvider(mainnet);
    sidechainGasProvider = new EstimatedGasProvider(sidechain);
  }

  public long getBridgePollInterval() {
    return bridgePollInterval;
  }

  public long getBridgePollTimeout() {
    return bridgePollTimeout;
  }

  public DataUnion deployDataUnion(
      final String name,
      final String admin,
      final double adminFeeFraction,
      final List<String> agents)
      throws Exception {
    if (adminFeeFraction < 0 || adminFeeFraction > 1) {
      throw new NumberFormatException("adminFeeFraction must be between 0 and 1");
    }
    return deployDataUnion(name, admin, toWei(adminFeeFraction), agents);
  }

  public DataUnion deployDataUnion(
      final String name,
      final String admin,
      final BigInteger adminFeeFractionWei,
      final List<String> agents)
      throws Exception {
    ArrayList<Address> agentAddresses = new ArrayList<Address>(agents.size());
    for (String agent : agents) {
      agentAddresses.add(new Address(agent));
    }
    Utf8String duname = new Utf8String(name);
    factoryMainnet()
        .deployNewDataUnion(
            new Address(admin),
            new Uint256(adminFeeFractionWei),
            new DynamicArray<Address>(Address.class, agentAddresses),
            duname)
        .send();
    Address mainnetAddress =
        factoryMainnet().mainnetAddress(new Address(mainnetCred.getAddress()), duname).send();
    String sidechainAddress = factoryMainnet().sidechainAddress(mainnetAddress).send().getValue();
    return new DataUnion(
        mainnetDataUnion(mainnetAddress.getValue()),
        mainnet,
        mainnetCred,
        sidechainDataUnion(sidechainAddress),
        sidechain,
        sidechainCred);
  }

  public DataUnion dataUnionFromName(final String name) throws Exception {
    String address =
        factoryMainnet()
            .mainnetAddress(new Address(mainnetCred.getAddress()), new Utf8String(name))
            .send()
            .getValue();
    return dataUnionFromMainnetAddress(address);
  }

  public DataUnion dataUnionFromMainnetAddress(final String mainnetAddress) throws Exception {
    DataUnionMainnet main = mainnetDataUnion(mainnetAddress);
    DataUnionSidechain side =
        sidechainDataUnion(
            factoryMainnet()
                .sidechainAddress(new Address(main.getContractAddress()))
                .send()
                .getValue());
    return new DataUnion(main, mainnet, mainnetCred, side, sidechain, sidechainCred);
  }

  protected DataUnionFactoryMainnet factoryMainnet() {
    return DataUnionFactoryMainnet.load(mainnetFactory, mainnet, mainnetCred, mainnetGasProvider);
  }

  protected DataUnionMainnet mainnetDataUnion(final String address) {
    return DataUnionMainnet.load(address, mainnet, mainnetCred, mainnetGasProvider);
  }

  protected DataUnionFactorySidechain factorySidechain() {
    return DataUnionFactorySidechain.load(
        sidechainFactory, sidechain, sidechainCred, sidechainGasProvider);
  }

  protected DataUnionSidechain sidechainDataUnion(final String address) {
    return DataUnionSidechain.load(address, sidechain, sidechainCred, sidechainGasProvider);
  }

  public String mainnetTokenAddress() throws Exception {
    return factoryMainnet().token().send().getValue();
  }

  public String sidechainTokenAddress() throws Exception {
    return factorySidechain().token().send().getValue();
  }

  protected IERC20 mainnetToken() throws Exception {
    String tokenAddress = factoryMainnet().token().send().getValue();
    return IERC20.load(tokenAddress, mainnet, mainnetCred, mainnetGasProvider);
  }

  protected IERC20 sidechainToken() throws Exception {
    String tokenAddress = factorySidechain().token().send().getValue();
    return IERC20.load(tokenAddress, sidechain, sidechainCred, sidechainGasProvider);
  }

  protected ForeignAMB mainnetAMB(final Credentials cred) throws Exception {
    String amb = factoryMainnet().amb().send().getValue();
    return ForeignAMB.load(amb, mainnet, cred, mainnetGasProvider);
  }

  protected ForeignAMB mainnetAMB() throws Exception {
    return mainnetAMB(mainnetCred);
  }

  protected HomeAMB sidechainAMB() throws Exception {
    String amb = factorySidechain().amb().send().getValue();
    return HomeAMB.load(amb, sidechain, sidechainCred, sidechainGasProvider);
  }

  /**
   * Waits until AMB message has been confirmed.
   *
   * @return number of signatures if complete or {@code null} if incomplete
   */
  protected BigInteger waitForSidechainAMBAffirmations(
      final Bytes32 msgHash, final long sleeptime, final long timeout) throws Exception {
    Condition affirmationsChange =
        new Condition() {
          @Override
          public Object check() throws Exception {
            BigInteger requiredSignatures = sidechainAMB().requiredSignatures().send().getValue();
            BigInteger signatures = sidechainAMB().numMessagesSigned(msgHash).send().getValue();
            // bit 255 is set in AMB to indicate completion. should the same as signatures >= reqd
            boolean complete = signatures.testBit(255);
            signatures = signatures.clearBit(255);
            if (!complete || signatures.compareTo(requiredSignatures) < 0) return null;
            return signatures;
          }
        };
    return (BigInteger) waitForCondition(affirmationsChange, sleeptime, timeout);
  }

  public BigInteger waitForSidechainBalanceChange(
      final BigInteger initialBalance,
      final String balanceAddress,
      final long sleeptime,
      final long timeout)
      throws Exception {
    return waitForErc20BalanceChange(
        initialBalance,
        sidechainToken().getContractAddress(),
        balanceAddress,
        sidechain,
        sleeptime,
        timeout);
  }

  public BigInteger waitForMainnetBalanceChange(
      final BigInteger initialBalance,
      final String balanceAddress,
      final long sleeptime,
      final long timeout)
      throws Exception {
    return waitForErc20BalanceChange(
        initialBalance,
        mainnetToken().getContractAddress(),
        balanceAddress,
        mainnet,
        sleeptime,
        timeout);
  }

  public Boolean waitForMainnetTx(final String txhash, final long sleeptime, final long timeout)
      throws Exception {
    return waitForTx(mainnet, txhash, sleeptime, timeout);
  }

  public Boolean waitForSidechainTx(final String txhash, final long sleeptime, final long timeout)
      throws Exception {
    return waitForTx(sidechain, txhash, sleeptime, timeout);
  }

  public void setNewDUInitialEth(final BigInteger amountWei) throws Exception {
    factorySidechain().setNewDUInitialEth(new Uint256(amountWei)).send();
  }

  public void setNewDUOwnerInitialEth(final BigInteger amountWei) throws Exception {
    factorySidechain().setNewDUOwnerInitialEth(new Uint256(amountWei)).send();
  }

  public void setNewMemberInitialEth(final BigInteger amountWei) throws Exception {
    factorySidechain().setNewMemberInitialEth(new Uint256(amountWei)).send();
  }

  /**
   * Port an AMB message that has required # signatures.
   *
   * <p>from foreign AMB hasEnoughValidSignatures(): Need to construct this binary blob:
   *
   * <ul>
   *   <li>First byte X is a number of signatures in a blob,
   *   <li>next X bytes are v components of signatures,
   *   <li>next 32 * X bytes are r components of signatures,
   *   <li>next 32 * X bytes are s components of signatures.
   * </ul>
   *
   * java byte is signed, so we use short to support 0-255
   *
   * @param msgHash AMB message hash
   */
  protected void portTxToMainnet(
      final Bytes32 msgHash, final short collectedSignatures, final Credentials cred)
      throws Exception {
    if (collectedSignatures > 255) {
      throw new UnsupportedOperationException("collectedSignatures cannot be greater than 255");
    }
    DynamicBytes message = sidechainAMB().message(msgHash).send();
    byte[] signatures = new byte[1 + (65 * collectedSignatures)];
    signatures[0] = (byte) collectedSignatures;
    // collect signatures one by one from sidechain, add to blob
    for (short i = 0; i < collectedSignatures; i++) {
      Sign.SignatureData sig =
          fromBytes65(sidechainAMB().signature(msgHash, new Uint256(i)).send().getValue());
      signatures[i + 1] = sig.getV()[0];
      System.arraycopy(sig.getR(), 0, signatures, 1 + collectedSignatures + (i * 32), 32);
      System.arraycopy(sig.getS(), 0, signatures, 1 + (collectedSignatures * 33) + (i * 32), 32);
    }
    mainnetAMB(cred).executeSignatures(message, new DynamicBytes(signatures)).send();
  }

  public void portTxsToMainnet(
      final EthereumTransactionReceipt withdrawalTransaction, final String mainnetSenderPrivateKey)
      throws Exception {
    portTxsToMainnet(withdrawalTransaction.tr, Credentials.create(mainnetSenderPrivateKey));
  }

  public void portTxsToMainnet(
      final EthereumTransactionReceipt withdrawalTransaction,
      final BigInteger mainnetSenderPrivateKey)
      throws Exception {
    portTxsToMainnet(
        withdrawalTransaction.tr, Credentials.create(ECKeyPair.create(mainnetSenderPrivateKey)));
  }

  public void portTxsToMainnet(final String withdrawTxHash, final String mainnetSenderPrivateKey)
      throws Exception {
    Optional<TransactionReceipt> optional =
        sidechain.ethGetTransactionReceipt(withdrawTxHash).send().getTransactionReceipt();
    if (!optional.isPresent()) {
      throw new NoSuchElementException(
          "No sidechain transaction found for txhash " + withdrawTxHash);
    }
    TransactionReceipt withdraw = optional.get();
    portTxsToMainnet(withdraw, Credentials.create(mainnetSenderPrivateKey));
  }

  protected void portTxsToMainnet(
      final TransactionReceipt withdraw, final Credentials mainnetSenderCredentials)
      throws Exception {
    List<Bytes32[]> msgs = extractAmbMessagesIdAndHash(withdraw);
    for (Bytes32[] msg : msgs) {
      Bytes32 id = msg[0];
      Bytes32 hash = msg[1];
      // check if message has already been sent
      if (mainnetAMB().messageCallStatus(id).send().getValue()
          || !mainnetAMB()
              .failedMessageSender(id)
              .send()
              .toUint()
              .getValue()
              .equals(BigInteger.ZERO)) {
        log.warn(
            "ForeignAMB has already seen msgId "
                + Numeric.toHexString(id.getValue())
                + ". skipping");
        continue;
      }
      // wait until required signatures are present
      BigInteger signatures =
          waitForSidechainAMBAffirmations(hash, bridgePollInterval, bridgePollTimeout);
      if (signatures == null) {
        log.warn("Couldnt find affimation for AMB msgId " + id);
        continue;
      }
      log.info("Porting msgId " + id);
      portTxToMainnet(hash, signatures.shortValueExact(), mainnetSenderCredentials);
    }
  }

  // utility functions:
  /**
   * @return a list of [msgId, msgHash] for the AMB messages generated by sidechain withdrawal tx
   */
  private static List<Bytes32[]> extractAmbMessagesIdAndHash(final TransactionReceipt tx) {
    ArrayList<Bytes32[]> msgs = new ArrayList<Bytes32[]>();
    for (Log l : tx.getLogs()) {
      EventValues vals = staticExtractEventParameters(HomeAMB.USERREQUESTFORSIGNATURE_EVENT, l);
      if (vals == null) {
        continue;
      }
      // event UserRequestForSignature(bytes32 indexed messageId, bytes encodedData);
      Bytes32[] idAndHash = new Bytes32[2];
      idAndHash[0] = (Bytes32) vals.getIndexedValues().get(0);
      idAndHash[1] =
          new Bytes32(Hash.sha3(((DynamicBytes) vals.getNonIndexedValues().get(0)).getValue()));
      msgs.add(idAndHash);
    }
    return msgs;
  }
}
