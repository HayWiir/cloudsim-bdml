package ch.uzh.ifi.csg.cloudsim.rda;

import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmScheduler;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModelSpecPowerHpProLiantMl110G4Xeon3040;

import ch.uzh.ifi.csg.cloudsim.rda.provisioners.BwProvisioner;
import ch.uzh.ifi.csg.cloudsim.rda.provisioners.RamProvisioner;
import ch.uzh.ifi.csg.cloudsim.rda.provisioners.StorageIOProvisioner;

/**
 * This RDA specific host, supports the resource dependency aware scheduling
 * mechanisms and represents like its superclass an individual host in the
 * datacenter that may run VMs.
 * <br/><br/>
 * It uses the RDA specific resource provisioners for a finer allocation that
 * works with decimal places (doubles). Because of this, some methods of the
 * superclass Host had to be overridden and adapted to work with the RDA
 * provisioners.
 * <br/><br/>
 * However, the central method that does process the workloads on the host is:
 * <br/><br/>
 * <code>public double updateVmsProcessing(double currentTime)</code>
 * <br/><br/>
 * This methods call then the scheduler the VM scheduler to reallocate the
 * resources for all VMs on the particular host. It supports scheduling for
 * multiple resources, such as CPU, RAM, network bandwidth and disk I/O.
 * 
 * 
 * @author Patrick A. Taddei
 */
public class RdaHost extends PowerHost {

	/** the min event time interval, when scarcity occurs */
	public double scarcitySchedulingInterval;

	/** storage I/O provisioner */
	public StorageIOProvisioner storageIOProvisioner;

	/** The RAM provisioner. */
	private RamProvisioner ramProvisioner;

	/** The BW provisioner. */
	private BwProvisioner bwProvisioner;

	/**
	 * Instantiates a new host with RDA capabilities.
	 * 
	 * @param id
	 *            the id
	 * @param ramProvisioner
	 *            the ram provisioner
	 * @param bwProvisioner
	 *            the bw provisioner
	 * @param storageIOProvisioner
	 *            the storage I/O provisioner
	 * @param storage
	 *            the storage
	 * @param peList
	 *            the pe list
	 * @param vmScheduler
	 *            the VM scheduler
	 * @param scarcitySchedulingInterval
	 *            The scheduling interval, if a resource is scarce.
	 */
	public RdaHost(int id, RamProvisioner ramProvisioner,
			BwProvisioner bwProvisioner,
			StorageIOProvisioner storageIOProvisioner, long storage,
			List<? extends Pe> peList, VmScheduler vmScheduler,
			double scarcitySchedulingInterval) {
		super(id, null, null, storage, peList, vmScheduler,
				new PowerModelSpecPowerHpProLiantMl110G4Xeon3040());
		this.storageIOProvisioner = storageIOProvisioner;
		this.scarcitySchedulingInterval = scarcitySchedulingInterval;
		this.bwProvisioner = bwProvisioner;
		this.ramProvisioner = ramProvisioner;
	}

	@Override
	public boolean vmCreate(Vm vm) {
		if (getStorage() < vm.getSize()) {
			Log.printLine("[VmScheduler.vmCreate] Allocation of VM #"+
					vm.getId()+ " to Host #" + getId() + " failed by storage");
			return false;
		}

		if (!this.ramProvisioner.allocateRamForVm(vm,
				vm.getCurrentRequestedRam())) {
			Log.printLine("[VmScheduler.vmCreate] Allocation of VM #" +
					vm.getId()+ " to Host #" + getId() + " failed by RAM");
			return false;
		}

		if (!this.bwProvisioner.allocateBwForVm(vm, vm.getCurrentRequestedBw())) {
			Log.printLine("[VmScheduler.vmCreate] Allocation of VM #" +
					vm.getId() + " to Host #" + getId() + " failed by BW");
			this.ramProvisioner.deallocateRamForVm(vm);
			return false;
		}

		if (!getVmScheduler()
				.allocatePesForVm(vm, vm.getCurrentRequestedMips())) {
			Log.printLine("[VmScheduler.vmCreate] Allocation of VM #" +
					vm.getId() + " to Host #" + getId() + " failed by MIPS");
			this.ramProvisioner.deallocateRamForVm(vm);
			this.bwProvisioner.deallocateBwForVm(vm);
			return false;
		}

		setStorage(getStorage() - vm.getSize());
		getVmList().add(vm);
		vm.setHost(this);
		return true;
	}

	@Override
	public boolean isSuitableForVm(Vm vm) {
		return (getVmScheduler().getPeCapacity() >= vm
				.getCurrentRequestedMaxMips()
				&& getVmScheduler().getAvailableMips() >= vm
						.getCurrentRequestedTotalMips()
				&& this.ramProvisioner.isSuitableForVm(vm,
						vm.getCurrentRequestedRam()) && this.bwProvisioner
					.isSuitableForVm(vm, vm.getCurrentRequestedBw()));
	}

	@Override
	public double updateVmsProcessing(double currentTime) {

		((RdaVmScheduler) getVmScheduler()).allocateResourcesForAllVms(
				currentTime, getVmList());

		double smallerTime = Double.MAX_VALUE;
		for (Vm vm : getVmList()) {
			double time = ((RdaVm) vm).updateVmProcessing(currentTime,
					getVmScheduler().getAllocatedMipsForVm(vm),
					((RdaVm) vm).getCurrentAllocatedBwFine(),
					((RdaVm) vm).getCurrentAllocatedStorageIO());

			if (time > 0.0 && time < smallerTime) {
				smallerTime = time;
			}
		}

		smallerTime = checkForScarcity(smallerTime, currentTime);

		updateHostState(currentTime);

		return smallerTime;
	}

	/**
	 * This method logs certain host specific measures and adds the history.
	 * This behavior was taken from the the HostDynamicWorkload class.
	 * 
	 * @param currentTime
	 *            the time of the simulation
	 */
	protected void updateHostState(double currentTime) {
		double hostTotalRequestedMips = 0;
		setPreviousUtilizationMips(getUtilizationMips());
		setUtilizationMips(0);

		for (Vm vm : getVmList()) {
			double totalRequestedMips = ((RdaVm) vm)
					.getCurrentRequestedTotalMips(currentTime);
			double totalAllocatedMips = getVmScheduler()
					.getTotalAllocatedMipsForVm(vm);

			if (!Log.isDisabled()) {
				Log.formatLine("%.6f: [Host #" + getId()
						+ "] Total allocated MIPS for VM #" + vm.getId()
						+ " (Host #" + vm.getHost().getId()
						+ ") is %.2f, was requested %.2f", CloudSim.clock(),
						totalAllocatedMips, totalRequestedMips);

			}

			if (getVmsMigratingIn().contains(vm)) {
				Log.formatLine(
						"%.6f: [Host #" + getId() + "] VM #" + vm.getId()
								+ " is being migrated to Host #" + getId(),
						CloudSim.clock());
			} else {
				if (totalAllocatedMips + 0.1 < totalRequestedMips) {
					Log.formatLine("%.6f: [Host #" + getId()
							+ "] Under allocated MIPS for VM #" + vm.getId()
							+ ": %.2f", CloudSim.clock(), totalRequestedMips
							- totalAllocatedMips);
				}

				vm.addStateHistoryEntry(currentTime, totalAllocatedMips,
						totalRequestedMips,
						(vm.isInMigration() && !getVmsMigratingIn()
								.contains(vm)));

				if (vm.isInMigration()) {
					Log.formatLine(
							"%.6f: [Host #" + getId() + "] VM #" + vm.getId()
									+ " is in migration", CloudSim.clock());
					totalAllocatedMips /= 0.9; // performance degradation due to
												// migration - 10%
				}
			}

			setUtilizationMips(getUtilizationMips() + totalAllocatedMips);
			hostTotalRequestedMips += totalRequestedMips;
		}

		addStateHistoryEntry(currentTime, getUtilizationMips(),
				hostTotalRequestedMips, (getUtilizationMips() > 0));
	}

	/**
	 * Checks, whether a scarcity could arise within the time span to the
	 * smallerTime. If so, the result will be the time, when the scarcity
	 * arises, the scarcitySchedulingInterval, when expected time is smaller
	 * than it.
	 * 
	 * @param smallerTime
	 *            the time, where the next event is expected
	 * @param currentTime
	 *            current simulation time
	 * @return smallerTime
	 */
	protected double checkForScarcity(double smallerTime, double currentTime) {

		double totalGradCpu = 0.0;
		double totalReqCpu = 0.0;
		double totalGradBw = 0.0;
		double totalReqBw = 0.0;
		double totalGradStorage = 0.0;
		double totalReqStorage = 0.0;
		for (Vm vm : getVmList()) {
			totalGradCpu += ((RdaVm) vm).getCurrentRequestedGradCpu();
			totalReqCpu += ((RdaVm) vm)
					.getCurrentRequestedTotalMips(currentTime);
			totalGradBw += ((RdaVm) vm).getCurrentRequestedGradBw();
			totalReqBw += ((RdaVm) vm).getCurrentRequestedBw(currentTime);
			totalGradStorage += ((RdaVm) vm).getCurrentRequestedGradStorageIO();
			totalReqStorage += ((RdaVm) vm)
					.getCurrentRequestedStorageIO(currentTime);
		}

		// check if time of a shortage will arrive before the next simulation
		// time.
		double max = getVmScheduler().getPeCapacity();
		double current = max - getVmScheduler().getMaxAvailableMips();
		double expectedTime = (max - current) / totalGradCpu;

		if (expectedTime > 0.0d && expectedTime < smallerTime) {
			// next expected shortage with linear increase of the resources
			if (expectedTime > smallerTime) {
				smallerTime = expectedTime;
			} else {
				smallerTime = this.scarcitySchedulingInterval;
			}
		} else if (expectedTime == 0.0d) {
			// max - current = 0 && expectedTime != NaN
			// --> currently there is a shortage
			smallerTime = this.scarcitySchedulingInterval;
		} else if (totalReqCpu > max) {
			// --> currently there is a shortage
			smallerTime = this.scarcitySchedulingInterval;
		}

		double maxBw = this.bwProvisioner.getBw();
		double currentBw = maxBw - this.bwProvisioner.getAvailableBw();
		double expectedTimeBw = (maxBw - currentBw) / totalGradBw;

		if (expectedTimeBw > 0.0d && expectedTimeBw < smallerTime) {
			// next expected shortage with linear increase of the resources
			smallerTime = expectedTimeBw;
			if (expectedTimeBw > smallerTime) {
				smallerTime = expectedTimeBw;
			} else {
				smallerTime = this.scarcitySchedulingInterval;
			}
		} else if (expectedTimeBw == 0.0d) {
			// max - current = 0 && expectedTime != NaN
			// --> currently there is a shortage
			smallerTime = this.scarcitySchedulingInterval;
		} else if (totalReqBw > maxBw) {
			// --> currently there is a shortage
			smallerTime = this.scarcitySchedulingInterval;
		}

		double maxStorageIO = this.storageIOProvisioner.getStorageIO();
		double currentStorageIO = maxStorageIO
				- this.storageIOProvisioner.getAvailableStorageIO();
		double expectedTimeStorageIO = (maxStorageIO - currentStorageIO)
				/ totalGradStorage;

		if (expectedTimeStorageIO > 0.0d && expectedTimeStorageIO < smallerTime) {
			// next expected shortage with linear increase of the resources
			if (expectedTimeStorageIO > smallerTime) {
				smallerTime = expectedTimeStorageIO;
			} else {
				smallerTime = this.scarcitySchedulingInterval;
			}
		} else if (expectedTimeStorageIO == 0.0d) {
			// max - current = 0 && expectedTime != NaN
			// --> currently there is a shortage
			smallerTime = this.scarcitySchedulingInterval;
		} else if (totalReqStorage > maxStorageIO) {
			// --> currently there is a shortage
			smallerTime = this.scarcitySchedulingInterval;
		}
		return smallerTime;
	}

	@Override
	public List<Vm> getCompletedVms() {
		List<Vm> vmsToRemove = new ArrayList<Vm>();
		for (Vm vm : getVmList()) {
			if (vm.isInMigration()) {
				continue;
			}

			// get total mips for the current VM
			List<Double> mips = ((RdaVm) vm).getCurrentAllocatedMips();

			if (mips == null) {
				vmsToRemove.add(vm);
			} else {

				double total = 0.0d;
				for (Double m : mips) {
					total += m;
				}

				// if there is no mips allocated, remove it.
				if (total == 0) {
					vmsToRemove.add(vm);
				}
			}
		}
		return vmsToRemove;
	}

	/**
	 * Retrieves the min event time interval, when scarcity occurs.
	 * 
	 * @return the min event time interval, when scarcity occurs
	 */
	public double getScarcityShedulingInterval() {
		return this.scarcitySchedulingInterval;
	}

	/**
	 * Setts the min event time interval, when scarcity occurs.
	 * 
	 * @param scarcitySchedulingInterval
	 *            the min event time interval, when scarcity occurs
	 */
	public void setScarcityShedulingInterval(double scarcitySchedulingInterval) {
		this.scarcitySchedulingInterval = scarcitySchedulingInterval;
	}

	@Override
	protected void vmDeallocate(Vm vm) {
		this.ramProvisioner.deallocateRamForVm(vm);
		this.bwProvisioner.deallocateBwForVm(vm);
		this.storageIOProvisioner.deallocateStorageIOForVm((RdaVm) vm);
		getVmScheduler().deallocatePesForVm(vm);
		setStorage(getStorage() + vm.getSize());
	}

	public StorageIOProvisioner getStorageIOProvisioner() {
		return storageIOProvisioner;
	}

	/**
	 * Gets the machine memory.
	 * 
	 * @return the machine memory
	 * @pre $none
	 * @post $result > 0
	 */
	public int getRam() {
		return (int) this.ramProvisioner.getRam();
	}
	/**
	 * Gets the machine bw.
	 * 
	 * @return the machine bw
	 * @pre $none
	 * @post $result > 0
	 */
	public long getBw() {
		return (long) this.bwProvisioner.getBw();
	}
}
