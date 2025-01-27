package net.preibisch.bigstitcher.spark;

import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.util.Util;
import net.preibisch.bigstitcher.spark.SparkAffineFusion.DataTypeFusion;
import net.preibisch.bigstitcher.spark.abstractcmdline.AbstractBasic;
import net.preibisch.bigstitcher.spark.util.Downsampling;
import net.preibisch.bigstitcher.spark.util.Import;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.export.ExportN5Api;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import util.URITools;

public class CreateFusionContainer extends AbstractBasic implements Callable<Void>, Serializable
{
	private static final long serialVersionUID = -9140450542904228386L;

	@Option(names = { "-o", "--outputPath" }, required = true, description = "OME-ZARR path for saving, e.g. -o /home/fused.zarr, file:/home/fused.n5 or e.g. s3://myBucket/data.zarr")
	private String outputPathURIString = null;

	@Option(names = {"-s", "--storage"}, defaultValue = "ZARR", showDefaultValue = CommandLine.Help.Visibility.ALWAYS,
			description = "Dataset storage type, currently supported OME-ZARR, N5, and ONLY for local, multithreaded Spark HDF5 (default: OME-ZARR)")
	private StorageFormat storageType = null;

	@Option(names = {"-ch", "--numChannels" }, description = "number of fused channels in the output container (default: as many as in the XML)")
	private Integer numChannels = null;

	@Option(names = {"-tp", "--numTimepoints" }, description = "number of fused timepoints in the output container (default: as many as in the XML)")
	private Integer numTimepoints = null;

	@Option(names = "--blockSize", description = "blockSize (default: 128,128,128)")
	private String blockSizeString = "128,128,128";

	@Option(names = {"-p", "--dataType"}, defaultValue = "FLOAT32", showDefaultValue = CommandLine.Help.Visibility.ALWAYS,
			description = "Data type, UINT8 [0...255], UINT16 [0...65535] and FLOAT32 are supported, when choosing UINT8 or UINT16 you must define min and max intensity (default: FLOAT32)")
	private DataTypeFusion dataTypeFusion = null;

	@Option(names = { "--bdv" }, required = false, description = "Write a BigDataViewer-compatible dataset (default: false)")
	private boolean bdv = false;

	@Option(names = { "-xo", "--xmlout" }, required = false, description = "path to the new BigDataViewer xml project (only valid if --bdv was selected), "
			+ "e.g. -xo /home/project.xml or -xo s3://myBucket/project.xml (default: dataset.xml in basepath for H5, dataset.xml one directory level above basepath for N5)")
	private String xmlOutURIString = null;

	@Option(names = { "-b", "--boundingBox" }, description = "fuse a specific bounding box listed in the XML (default: fuse everything)")
	private String boundingBoxName = null;

	@Option(names = { "--multiRes" }, description = "Automatically create a multi-resolution pyramid (default: false)")
	private boolean multiRes = false;

	@Option(names = { "-ds", "--downsampling" }, split = ";", required = false, description = "Manually define steps to create of a multi-resolution pyramid (e.g. -ds 2,2,1; 2,2,1; 2,2,2; 2,2,2)")
	private List<String> downsampling = null;

	@Option(names = { "--preserveAnisotropy" }, description = "preserve the anisotropy of the data (default: false)")
	private boolean preserveAnisotropy = false;

	@Option(names = { "--anisotropyFactor" }, description = "define the anisotropy factor if preserveAnisotropy is set to true (default: compute from data)")
	private double anisotropyFactor = Double.NaN;

	URI outPathURI = null, xmlOutURI = null;

	@Override
	public Void call() throws Exception
	{
		if (dryRun)
		{
			System.out.println( "dry-run not supported for CreateFusionContainer.");
			System.exit( 0 );
		}

		if ( this.bdv && xmlOutURIString == null )
		{
			System.out.println( "Please specify the output XML for the BDV dataset: -xo");
			return null;
		}

		final SpimData2 dataGlobal = this.loadSpimData2();

		if ( dataGlobal == null )
			return null;

		final ArrayList< ViewId > viewIdsGlobal = Import.getViewIds( dataGlobal );

		if ( viewIdsGlobal == null || viewIdsGlobal.size() == 0 )
			return null;

		final int numTimepointsXML = dataGlobal.getSequenceDescription().getTimePoints().getTimePointsOrdered().size();
		final int numChannelsXML = dataGlobal.getSequenceDescription().getAllChannelsOrdered().size();

		System.out.println( "XML project contains " + numChannelsXML + " channels, " + numTimepointsXML + " timepoints." );

		if ( numChannels == null )
			numChannels = numChannelsXML;

		if ( numTimepoints == null )
			numTimepoints = numTimepointsXML;

		if ( numChannels < numChannelsXML )
			System.out.println( "WARNING: you selected to fuse LESS channels than present in the data. This works, but you will need specify the content manually.");
		else if ( numChannels > numChannelsXML )
			System.out.println( "WARNING: you selected to fuse MORE channels than present in the data. This works, but you will need specify the content manually.");

		if ( numTimepoints < numTimepointsXML )
			System.out.println( "WARNING: you selected to fuse LESS timepoints than present in the data. This works, but you will need specify the content manually.");
		else if ( numTimepoints > numTimepointsXML )
			System.out.println( "WARNING: you selected to fuse MORE timepoints than present in the data. This works, but you will need specify the content manually.");

		this.outPathURI =  URITools.toURI( outputPathURIString );
		System.out.println( "ZARR/N5/HDF5 container: " + outPathURI );

		if ( this.bdv )
		{
			this.xmlOutURI = URITools.toURI( xmlOutURIString );
			System.out.println( "XML: " + xmlOutURI );

			if ( storageType == StorageFormat.ZARR )
			{
				System.out.println( "BDV project for OME-ZARR not yet supported (but very soon!)" );
				return null;
			}
		}

		BoundingBox boundingBox = Import.getBoundingBox( dataGlobal, viewIdsGlobal, boundingBoxName );

		final long[] minBB = boundingBox.minAsLongArray();
		final long[] maxBB = boundingBox.maxAsLongArray();

		if ( preserveAnisotropy )
		{
			System.out.println( "Preserving anisotropy.");

			if ( Double.isNaN( anisotropyFactor ) )
			{
				anisotropyFactor = TransformationTools.getAverageAnisotropyFactor( dataGlobal, viewIdsGlobal );

				System.out.println( "Anisotropy factor [computed from data]: " + anisotropyFactor );
			}
			else
			{
				System.out.println( "Anisotropy factor [provided]: " + anisotropyFactor );
			}

			// prepare downsampled boundingbox
			minBB[ 2 ] = Math.round( Math.floor( minBB[ 2 ] / anisotropyFactor ) );
			maxBB[ 2 ] = Math.round( Math.ceil( maxBB[ 2 ] / anisotropyFactor ) );

			boundingBox = new BoundingBox( new FinalInterval(minBB, maxBB) );

			System.out.println( "Adjusted bounding box (anisotropy preserved: " + Util.printInterval( boundingBox ) );
		}

		final int[] blockSize = Import.csvStringToIntArray( blockSizeString );

		System.out.println( "Fusion target: " + boundingBox.getTitle() + ": " + Util.printInterval( boundingBox ) + " with blocksize " + Util.printCoordinates( blockSize ) );

		//
		// set up downsampling (if wanted)
		//
		if ( !Downsampling.testDownsamplingParameters( this.multiRes, this.downsampling ) )
			return null;

		final int[][] downsamplings;

		if ( multiRes )
			downsamplings = ExportN5Api.estimateMultiResPyramid( new FinalDimensions( boundingBox ), anisotropyFactor );
		else if ( this.downsampling != null )
			downsamplings = Import.csvStringListToDownsampling( this.downsampling );
		else
			downsamplings = new int[][]{{ 1, 1, 1 }};

		System.out.println( "The following downsampling pyramid will be created:" );
		System.out.println( Arrays.deepToString( downsamplings ) );

		// TODO Auto-generated method stub
		return null;
	}

	public static void main(final String... args) throws SpimDataException
	{

		//final XmlIoSpimData io = new XmlIoSpimData();
		//final SpimData spimData = io.load( "/Users/preibischs/Documents/Microscopy/Stitching/Truman/standard/output/dataset.xml" );
		//BdvFunctions.show( spimData );
		//SimpleMultiThreading.threadHaltUnClean();

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new CreateFusionContainer()).execute(args));
	}
}
