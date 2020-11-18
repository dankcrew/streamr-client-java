package com.streamr.client.dataunion;

import com.streamr.client.dataunion.contracts.DataUnionMainnet;
import com.streamr.client.dataunion.contracts.DataUnionSidechain;
import com.streamr.client.utils.Web3jUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.TypeEncoder;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.utils.Numeric;
import java.math.BigInteger;
import static com.streamr.client.utils.Web3jUtils.*;

public class DataUnion {
    private static final Logger log = LoggerFactory.getLogger(DataUnion.class);
    public enum ActiveStatus{NONE, ACTIVE, INACTIVE};

    private DataUnionMainnet mainnet;
    private DataUnionSidechain sidechain;
    private Web3j mainnetConnector, sidechainConnector;
    private Credentials mainnetCred, sidechainCred;

    //use DataUnionClient to instantiate
    protected DataUnion(DataUnionMainnet mainnet, Web3j mainnetConnector, Credentials mainnetCred, DataUnionSidechain sidechain, Web3j sidechainConnector, Credentials sidechainCred) {
        this.mainnet = mainnet;
        this.mainnetConnector = mainnetConnector;
        this.mainnetCred = mainnetCred;
        this.sidechain = sidechain;
        this.sidechainConnector = sidechainConnector;
        this.sidechainCred = sidechainCred;
    }

    public boolean waitForDeployment(long pollInterval, long timeout) throws Exception {
        String code = waitForCodeAtAddress(sidechain.getContractAddress(), sidechainConnector, pollInterval, timeout);
        return code != null && !code.equals("0x");
    }

    public String getMainnetContractAddress(){
        return mainnet.getContractAddress();
    }

    public String getSidechainContractAddress(){
        return sidechain.getContractAddress();
    }

    public boolean isDeployed() throws Exception {
        //this will check the condition only once:
        return waitForDeployment(0,0);
    }

    public void sendTokensToBridge() throws Exception {
        mainnet.sendTokensToBridge().send();
    }

    public BigInteger waitForEarningsChange(final BigInteger initialBalance, long pollInterval, long timeout) throws Exception {
        Web3jUtils.Condition earningsChange = new Web3jUtils.Condition(){
            @Override
            public Object check() throws Exception {
                BigInteger bal = sidechain.totalEarnings().send().getValue();
                if(!bal.equals(initialBalance)) {
                    return bal;
                }
                return null;
            }
        };
        return (BigInteger) waitForCondition(earningsChange, pollInterval, timeout);
    }

    public EthereumTransactionReceipt addJoinPartAgents(String ... agents) throws Exception {
        return new EthereumTransactionReceipt(sidechain.addJoinPartAgents(asDynamicAddressArray(agents)).send());
    }

    public EthereumTransactionReceipt partMembers(String ... members) throws Exception {
        return new EthereumTransactionReceipt(sidechain.partMembers(asDynamicAddressArray(members)).send());
    }

    public EthereumTransactionReceipt joinMembers(String ... members) throws Exception {
        return new EthereumTransactionReceipt(sidechain.addMembers(asDynamicAddressArray(members)).send());
    }


    /**
     *
     * withdraw a member to his own address. Must be that member or admin.

     * @param member
     * @param amount amout in wei or 0 to withdraw everything
     * @return
     * @throws Exception
     */
    public EthereumTransactionReceipt withdrawMember(String member, BigInteger amount) throws Exception {
        if(amount.equals(BigInteger.ZERO)) {
            return new EthereumTransactionReceipt(sidechain.withdrawAll(new Address(member), new Bool(true)).send());
        }
        else {
            return new EthereumTransactionReceipt(sidechain.withdraw(new Address(member), new Uint256(amount), new Bool(true)).send());
        }
    }

    /**
     *
     * @param amount amout in wei or 0 to withdraw everything
     * @return
     * @throws Exception
     */
    public EthereumTransactionReceipt withdrawSelf(BigInteger amount) throws Exception {
        return withdrawSelf(sidechainCred.getAddress(), amount);
    }

    /**
     * withdraw your own balance to another address
     *
     * @param to
     * @param amount amout in wei or 0 to withdraw everything
     * @return
     * @throws Exception
     */
    public EthereumTransactionReceipt withdrawSelf(String to, BigInteger amount) throws Exception {
        if(amount.equals(BigInteger.ZERO)) {
            return new EthereumTransactionReceipt(sidechain.withdrawAllTo(new Address(to), new Bool(true)).send());
        }
        else {
            return new EthereumTransactionReceipt(sidechain.withdrawTo(new Address(to), new Uint256(amount), new Bool(true)).send());
        }
    }

    /**
     *
     * @param withdrawerPrivateKey
     * @param to
     * @param amount amout in wei or 0 to withdraw everything
     * @return
     * @throws Exception
     */
    public EthereumTransactionReceipt withdraw(BigInteger withdrawerPrivateKey, String to, BigInteger amount) throws Exception {
        return withdraw(Credentials.create(ECKeyPair.create(withdrawerPrivateKey)), to, amount);
    }

    /**
     *
     * @param withdrawerPrivateKey
     * @param to
     * @param amount amout in wei or 0 to withdraw everything
     * @return
     * @throws Exception
     */
    public EthereumTransactionReceipt withdraw(String withdrawerPrivateKey, String to, BigInteger amount) throws Exception {
        return withdraw(Credentials.create(withdrawerPrivateKey), to, amount);
    }

    protected EthereumTransactionReceipt withdraw(Credentials from, String to, BigInteger amount) throws Exception {
        byte[] req = createWithdrawRequest(from.getAddress(), to, amount);
        byte[] sig = toBytes65(Sign.signPrefixedMessage(req, from.getEcKeyPair()));

        if(amount.equals(BigInteger.ZERO)) {
            return new EthereumTransactionReceipt(sidechain.withdrawAllToSigned(new Address(from.getAddress()), new Address(to), new Bool(true), new DynamicBytes(sig)).send());
        }
        else {
            return new EthereumTransactionReceipt(sidechain.withdrawToSigned(new Address(from.getAddress()), new Address(to), new Uint256(amount), new Bool(true), new DynamicBytes(sig)).send());
        }
    }

    public BigInteger totalEarnings() throws Exception {
        return sidechain.totalEarnings().send().getValue();
    }

    public BigInteger totalEarningsWithdrawn() throws Exception {
        return sidechain.totalEarningsWithdrawn().send().getValue();
    }

    public BigInteger activeMemberCount() throws Exception {
        return sidechain.activeMemberCount().send().getValue();
    }

    public BigInteger inactiveMemberCount() throws Exception {
        return sidechain.inactiveMemberCount().send().getValue();
    }

    public BigInteger lifetimeMemberEarnings() throws Exception {
        return sidechain.lifetimeMemberEarnings().send().getValue();
    }

    public BigInteger joinPartAgentCount() throws Exception {
        return sidechain.joinPartAgentCount().send().getValue();
    }

    public BigInteger getEarnings(String member) throws Exception {
        return sidechain.getEarnings(new Address(member)).send().getValue();
    }

    public BigInteger getWithdrawn(String member) throws Exception {
        return sidechain.getWithdrawn(new Address(member)).send().getValue();
    }

    public BigInteger getWithdrawableEarnings(String member) throws Exception {
        return sidechain.getWithdrawableEarnings(new Address(member)).send().getValue();
    }

    //create unsigned blob. must be signed to submit
    protected byte[] createWithdrawAllRequest(String from, String to) throws Exception {
        return createWithdrawRequest(from, to, BigInteger.ZERO);
    }

    protected byte[] createWithdrawRequest(String from, String to, BigInteger amount) throws Exception {
        Uint256 withdrawn = sidechain.getWithdrawn(new Address(from)).send();
        //TypeEncode doesnt expose a non-padding encode() :(
        String messageHex = TypeEncoder.encode(new Address(to)).substring(24) +
                TypeEncoder.encode(new Uint256(amount)) +
                TypeEncoder.encode(new Address(sidechain.getContractAddress())).substring(24) +
                TypeEncoder.encode(withdrawn);
        return Numeric.hexStringToByteArray(messageHex);
    }

    public boolean isMemberActive(String member) throws Exception {
        return ActiveStatus.ACTIVE.ordinal() == sidechain.memberData(new Address(member)).send().component1().getValue().longValue();
    }

}
