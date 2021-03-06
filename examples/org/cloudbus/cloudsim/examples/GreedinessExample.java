package org.cloudbus.cloudsim.examples;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerVmAllocationPolicySimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;

import ch.uzh.ifi.csg.cloudsim.rda.RdaCloudlet;
import ch.uzh.ifi.csg.cloudsim.rda.RdaCloudletSchedulerDynamicWorkload;
import ch.uzh.ifi.csg.cloudsim.rda.RdaVm;
import ch.uzh.ifi.csg.cloudsim.rda.greediness.VmSchedulerGreedinessAllocationAlgorithm;
import ch.uzh.ifi.csg.cloudsim.rda.provisioners.BwProvisionerSimple;
import ch.uzh.ifi.csg.cloudsim.rda.provisioners.RamProvisionerSimple;
import ch.uzh.ifi.csg.cloudsim.rda.provisioners.StorageIOProvisionerSimple;
import ch.uzh.ifi.csg.cloudsim.rda.useraware.RdaHostUserAware;
import ch.uzh.ifi.csg.cloudsim.rda.useraware.UserAwareDatacenter;

/**
 * A simple example showing how the greediness algorithm can be invoked.
 */
public class GreedinessExample {

	/** The cloudlet list. */
	private static List<Cloudlet> cloudletList;
	/** The vmlist. */
	private static List<Vm> vmlist;

	/** the minimal scheduling interval between events */
	private static double schedulingInterval = 0.000000001; // nano second

	/** the maximal scheduling interval if scarcity occurs on a host */
	private static double scarcitySchedulingInterval = 0.01; // milli second

	/** Record output to a CSV file */
	private static boolean record = true;

	/** path to the python binary on your system */
	private static String pythonPath = "python3 resources/python/";

	/**
	 * Creates main() to run this example.
	 *
	 * @param args
	 *            the args
	 */
	@SuppressWarnings("unused")
	public static void main(String[] args) {
		Log.printLine("Starting ...");

		try {
			// First step: Initialize the CloudSim package. It should be called
			// before creating any entities.
			int num_user = 2; // number of cloud users
			Calendar calendar = Calendar.getInstance(); // Calendar whose fields
														// have been initialized
														// with the current date
														// and time.
			boolean trace_flag = false; // trace events

			
			CloudSim.init(num_user, calendar, trace_flag, schedulingInterval);

			// Second step: Create Datacenters
			// Datacenters are the resource providers in CloudSim. We need at
			// list one of them to run a CloudSim simulation
			Datacenter datacenter0 = createDatacenter("Datacenter_01");

			// Third step: Create Broker
			DatacenterBroker broker = createBroker();
			int brokerId = broker.getId();

			// Fourth step: Create one virtual machine
			vmlist = new ArrayList<Vm>();

			// VM description
			int vmid = 0;
			int mips = 500;
			long size = 10000; // image size (MB)
			int ram = 512; // vm memory (MB)
			long bw = 500;
			int pesNumber = 1; // number of cpus
			String vmm = "Xen"; // VMM name

			Vm vm = new RdaVm(vmid, brokerId, mips, pesNumber, ram, bw, size,
					1, vmm, new RdaCloudletSchedulerDynamicWorkload(mips,
							pesNumber, scarcitySchedulingInterval),
					schedulingInterval);
			((RdaVm) vm).setCustomer("user0");
			vmlist.add(vm);

			vm = new RdaVm(1, brokerId, mips, pesNumber, ram, bw, size, 1, vmm,
					new RdaCloudletSchedulerDynamicWorkload(mips, pesNumber,
							scarcitySchedulingInterval), schedulingInterval);
			((RdaVm) vm).setCustomer("user1");
			vmlist.add(vm);

			// submit vm list to the broker
			broker.submitVmList(vmlist);

			// Fifth step: Create one Cloudlet
			cloudletList = new ArrayList<Cloudlet>();

			// Cloudlet properties
			int id = 0;
			long length = 4000;
			long fileSize = 300;
			long outputSize = 300;

			Cloudlet cloudlet = new RdaCloudlet(1, pesNumber, fileSize,
					outputSize, "resources/input1.csv", record);
			cloudlet.setUserId(brokerId);
			cloudlet.setVmId(0);
			cloudletList.add(cloudlet);

			cloudlet = new RdaCloudlet(2, pesNumber, fileSize, outputSize,
					"resources/input2.csv", record);
			cloudlet.setUserId(brokerId);
			cloudlet.setVmId(1);
			cloudletList.add(cloudlet);

			// cloudletList.add(cloudlet);

			// submit cloudlet list to the broker
			broker.submitCloudletList(cloudletList);

			// Sixth step: Starts the simulation
			CloudSim.startSimulation();

			CloudSim.stopSimulation();

			// Final step: Print results when simulation is over
			List<Cloudlet> newList = broker.getCloudletReceivedList();
			printCloudletList(newList);

			Log.printLine("Test finished!");
		} catch (Exception e) {
			e.printStackTrace();
			Log.printLine("Unwanted errors happen");
		}
	}

	/**
	 * Creates the datacenter.
	 *
	 * @param name
	 *            the name
	 *
	 * @return the datacenter
	 */
	private static Datacenter createDatacenter(String name) {

		// Here are the steps needed to create a PowerDatacenter:
		// 1. We need to create a list to store
		// our machine
		List<Host> hostList = new ArrayList<Host>();

		// 2. A Machine contains one or more PEs or CPUs/Cores.
		// In this example, it will have only one core.
		List<Pe> peList = new ArrayList<Pe>();

		int mips = 1000;

		// 3. Create PEs and add these into a list.
		peList.add(new Pe(0, new PeProvisionerSimple(mips))); // need to store
																// Pe id and
																// MIPS Rating

		// 4. Create Host with its id and list of PEs and add them to the list
		// of machines
		int hostId = 0;
		int ram = 2048; // host memory (MB)
		long storage = 1000000; // host storage
		int bw = 1000;

		RamProvisionerSimple ramProvisioner = new RamProvisionerSimple(ram);
		BwProvisionerSimple bwProvisioner = new BwProvisionerSimple(bw);
		StorageIOProvisionerSimple storageIO = new StorageIOProvisionerSimple(
				10000);
		hostList.add(new RdaHostUserAware(hostId, ramProvisioner,
				bwProvisioner, storageIO, storage, peList,
				new VmSchedulerGreedinessAllocationAlgorithm(peList,
						ramProvisioner, bwProvisioner, storageIO, pythonPath),
				scarcitySchedulingInterval)); // This
		// is
		// our
		// machine

		// 5. Create a DatacenterCharacteristics object that stores the
		// properties of a data center: architecture, OS, list of
		// Machines, allocation policy: time- or space-shared, time zone
		// and its price (G$/Pe time unit).
		String arch = "x86"; // system architecture
		String os = "Linux"; // operating system
		String vmm = "Xen";
		double time_zone = 10.0; // time zone this resource located
		double cost = 3.0; // the cost of using processing in this resource
		double costPerMem = 0.05; // the cost of using memory in this resource
		double costPerStorage = 0.001; // the cost of using storage in this
										// resource
		double costPerBw = 0.0; // the cost of using bw in this resource
		LinkedList<Storage> storageList = new LinkedList<Storage>(); // we are
																		// not
																		// adding
																		// SAN
		// devices by now

		DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
				arch, os, vmm, hostList, time_zone, cost, costPerMem,
				costPerStorage, costPerBw);

		// 6. Finally, we need to create a PowerDatacenter object.
		Datacenter datacenter = null;
		try {
			datacenter = new UserAwareDatacenter(name, characteristics,
					new PowerVmAllocationPolicySimple(hostList), storageList,
					schedulingInterval);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return datacenter;
	}

	/**
	 * Creates the broker.
	 *
	 * @return the datacenter broker
	 */
	private static DatacenterBroker createBroker() {
		DatacenterBroker broker = null;
		try {
			broker = new DatacenterBroker("Broker");
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		return broker;
	}

	/**
	 * Prints the Cloudlet objects.
	 *
	 * @param list
	 *            list of Cloudlets
	 */
	private static void printCloudletList(List<Cloudlet> list) {
		int size = list.size();
		Cloudlet cloudlet;

		String indent = "    ";
		Log.printLine();
		Log.printLine("========== OUTPUT ==========");
		Log.printLine("Cloudlet ID" + indent + "STATUS" + indent
				+ "Data center ID" + indent + "VM ID" + indent + "Time"
				+ indent + indent + indent + indent + "Start Time" + indent
				+ "Finish Time");

		DecimalFormat dft = new DecimalFormat("###.##");
		for (int i = 0; i < size; i++) {
			cloudlet = list.get(i);
			Log.print(indent + cloudlet.getCloudletId() + indent + indent);

			if (cloudlet.getCloudletStatus() == Cloudlet.SUCCESS) {
				Log.print("SUCCESS");

				Log.printLine(indent + indent + cloudlet.getResourceId()
						+ indent + indent + indent + cloudlet.getVmId()
						+ indent + indent
						+ Math.round(cloudlet.getActualCPUTime() * 1000000)
						/ 1000000.0 + indent + indent
						+ dft.format(cloudlet.getExecStartTime()) + indent
						+ indent + dft.format(cloudlet.getFinishTime()));
			}

			// Log.printLine(cloudlet.getCloudletHistory());

		}
	}
}