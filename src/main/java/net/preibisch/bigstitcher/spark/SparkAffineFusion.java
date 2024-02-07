package net.preibisch.bigstitcher.spark;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import mpicbg.spim.data.registration.ViewRegistrations;
import net.preibisch.bigstitcher.spark.util.ViewUtil.PrefetchPixel;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.VoidFunction;
import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.bigstitcher.spark.abstractcmdline.AbstractSelectableViews;
import net.preibisch.bigstitcher.spark.util.BDVSparkInstantiateViewSetup;
import net.preibisch.bigstitcher.spark.util.Downsampling;
import net.preibisch.bigstitcher.spark.util.Grid;
import net.preibisch.bigstitcher.spark.util.Import;
import net.preibisch.bigstitcher.spark.util.N5Util;
import net.preibisch.bigstitcher.spark.util.Spark;
import net.preibisch.bigstitcher.spark.util.ViewUtil;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.export.ExportN5API.StorageType;
import net.preibisch.mvrecon.process.export.ExportTools;
import net.preibisch.mvrecon.process.export.ExportTools.InstantiateViewSetup;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class SparkAffineFusion extends AbstractSelectableViews implements Callable<Void>, Serializable
{
	private static final long serialVersionUID = -6103761116219617153L;

	@Option(names = { "-o", "--n5Path" }, required = true, description = "N5/ZARR/HDF5 basse path for saving (must be combined with the option '-d' or '--bdv'), e.g. -o /home/fused.n5")
	private String n5Path = null;

	@Option(names = { "-d", "--n5Dataset" }, required = false, description = "Custom N5/ZARR/HDF5 dataset - it must end with '/s0' to be able to compute a multi-resolution pyramid, e.g. -d /ch488/s0")
	private String n5Dataset = null;

	@Option(names = { "--bdv" }, required = false, description = "Write a BigDataViewer-compatible dataset specifying TimepointID, ViewSetupId, e.g. --bdv 0,0 or --bdv 4,1")
	private String bdvString = null;

	@Option(names = { "-xo", "--xmlout" }, required = false, description = "path to the new BigDataViewer xml project (only valid if --bdv was selected), e.g. -xo /home/project.xml (default: dataset.xml in basepath for H5, dataset.xml one directory level above basepath for N5)")
	private String xmlOutPath = null;

	@Option(names = {"-s", "--storage"}, defaultValue = "N5", showDefaultValue = CommandLine.Help.Visibility.ALWAYS,
			description = "Dataset storage type, currently supported N5, ZARR (and ONLY for local, multithreaded Spark: HDF5)")
	private StorageType storageType = null;

	@Option(names = "--blockSize", description = "blockSize, you can use smaller blocks for HDF5 (default: 128,128,128)")
	private String blockSizeString = "128,128,128";

	@Option(names = "--blocksPerJob", description = "super-block multiplier, each spark job processes one super-block, for example \"2,2,2\" means 8 blocks per job (default: 1,1,1)")
	private String blocksPerJobString = "1,1,1";

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

	// TODO: make a variable just as -s is
	@Option(names = { "--UINT16" }, description = "save as UINT16 [0...65535], if you choose it you must define min and max intensity (default: fuse as 32 bit float)")
	private boolean uint16 = false;

	@Option(names = { "--UINT8" }, description = "save as UINT8 [0...255], if you choose it you must define min and max intensity (default: fuse as 32 bit float)")
	private boolean uint8 = false;

	@Option(names = { "--minIntensity" }, description = "min intensity for scaling values to the desired range (required for UINT8 and UINT16), e.g. 0.0")
	private Double minIntensity = null;

	@Option(names = { "--maxIntensity" }, description = "max intensity for scaling values to the desired range (required for UINT8 and UINT16), e.g. 2048.0")
	private Double maxIntensity = null;

	// TODO: support create custom downsampling pyramids, null is fine for now (used by multiRes later)
	private int[][] downsamplings;

	@Override
	public Void call() throws Exception
	{
		if (dryRun)
		{
			System.out.println( "dry-run not supported for affine fusion.");
			System.exit( 0 );
		}

		if ( (this.n5Dataset == null && this.bdvString == null) || (this.n5Dataset != null && this.bdvString != null) )
		{
			System.out.println( "You must define either the n5dataset (e.g. -d /ch488/s0) - OR - the BigDataViewer specification (e.g. --bdv 0,1)");
			return null;
		}

		Import.validateInputParameters(uint8, uint16, minIntensity, maxIntensity);

		if ( StorageType.HDF5.equals( storageType ) && bdvString != null && !uint16 )
		{
			System.out.println( "BDV-compatible HDF5 only supports 16-bit output for now. Please use '--UINT16' flag for fusion." );
			return null;
		}

		final SpimData2 dataGlobal = this.loadSpimData2();

		if ( dataGlobal == null )
			return null;

		final ArrayList< ViewId > viewIdsGlobal = this.loadViewIds( dataGlobal );

		if ( viewIdsGlobal == null || viewIdsGlobal.size() == 0 )
			return null;

		BoundingBox boundingBox = Import.getBoundingBox( dataGlobal, viewIdsGlobal, boundingBoxName );

		final int[] blockSize = Import.csvStringToIntArray(blockSizeString);
		final int[] blocksPerJob = Import.csvStringToIntArray(blocksPerJobString);
		System.out.println( "Fusing: " + boundingBox.getTitle() +
				": " + Util.printInterval( boundingBox ) +
				" with blocksize " + Util.printCoordinates( blockSize ) +
				" and " + Util.printCoordinates( blocksPerJob ) + " blocks per job" );

		final DataType dataType;

		if ( uint8 )
		{
			System.out.println( "Fusing to UINT8, min intensity = " + minIntensity + ", max intensity = " + maxIntensity );
			dataType = DataType.UINT8;
		}
		else if ( uint16 && bdvString != null && StorageType.HDF5.equals( storageType ) )
		{
			System.out.println( "Fusing to INT16 (for BDV compliance, which is treated as UINT16), min intensity = " + minIntensity + ", max intensity = " + maxIntensity );
			dataType = DataType.INT16;
		}
		else if ( uint16 )
		{
			System.out.println( "Fusing to UINT16, min intensity = " + minIntensity + ", max intensity = " + maxIntensity );
			dataType = DataType.UINT16;
		}
		else
		{
			System.out.println( "Fusing to FLOAT32" );
			dataType = DataType.FLOAT32;
		}

		//
		// final variables for Spark
		//
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

		//
		// set up downsampling (if wanted)
		//
		if ( !Downsampling.testDownsamplingParameters( this.multiRes, this.downsampling, this.n5Dataset ) )
			return null;

		if ( multiRes )
			downsamplings = ExportTools.estimateMultiResPyramid( new FinalDimensions( boundingBox ), anisotropyFactor );
		else if ( this.downsampling != null )
			downsamplings = Import.csvStringListToDownsampling( this.downsampling );
		else
			downsamplings = null;

		final long[] dimensions = boundingBox.dimensionsAsLongArray();

		// display virtually
		//final RandomAccessibleInterval< FloatType > virtual = FusionTools.fuseVirtual( data, viewIds, bb, Double.NaN ).getA();
		//new ImageJ();
		//ImageJFunctions.show( virtual, Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() ) );
		//SimpleMultiThreading.threadHaltUnClean();

		final String n5Path = this.n5Path;
		final String n5Dataset = this.n5Dataset != null ? this.n5Dataset : Import.createBDVPath( this.bdvString, this.storageType );
		final String xmlPath = this.xmlPath;
		final StorageType storageType = this.storageType;
		final Compression compression = new GzipCompression( 1 );

		final boolean uint8 = this.uint8;
		final boolean uint16 = this.uint16;
		final double minIntensity = (uint8 || uint16 ) ? this.minIntensity : 0;
		final double range;
		if ( uint8 )
			range = ( this.maxIntensity - this.minIntensity ) / 255.0;
		else if ( uint16 )
			range = ( this.maxIntensity - this.minIntensity ) / 65535.0;
		else
			range = 0;

		// TODO: improve (e.g. make ViewId serializable)
		final int[][] serializedViewIds = Spark.serializeViewIds(viewIdsGlobal);
		final boolean useAF = preserveAnisotropy;
		final double af = anisotropyFactor;

		try
		{
			// trigger the N5-blosc error, because if it is triggered for the first
			// time inside Spark, everything crashes
			new N5FSWriter(null);
		}
		catch (Exception e ) {}

		final N5Writer driverVolumeWriter = N5Util.createWriter( n5Path, storageType );

		System.out.println( "Format being written: " + storageType );

		driverVolumeWriter.createDataset(
				n5Dataset,
				dimensions,
				blockSize,
				dataType,
				compression );

		// using bigger blocksizes than being stored for efficiency (needed for very large datasets)
		final int[] superBlockSize = new int[ 3 ];
		Arrays.setAll( superBlockSize, d -> blockSize[ d ] * blocksPerJob[ d ] );
		final List<long[][]> grid = Grid.create(dimensions,
				superBlockSize,
				blockSize);

		System.out.println( "numJobs = " + grid.size() );

		driverVolumeWriter.setAttribute( n5Dataset, "offset", minBB );

		// saving metadata if it is bdv-compatible (we do this first since it might fail)
		if ( bdvString != null )
		{
			// A Functional Interface that converts a ViewId to a ViewSetup, only called if the ViewSetup does not exist
			final InstantiateViewSetup instantiate =
					new BDVSparkInstantiateViewSetup( angleIds, illuminationIds, channelIds, tileIds );

			final ViewId viewId = Import.getViewId( bdvString );

			try
			{
				if ( !ExportTools.writeBDVMetaData(
						driverVolumeWriter,
						storageType,
						dataType,
						dimensions,
						compression,
						blockSize,
						downsamplings,
						viewId,
						this.n5Path,
						this.xmlOutPath,
						instantiate ) )
				{
					System.out.println( "Failed to write metadata for '" + n5Dataset + "'." );
					return null;
				}
			}
			catch (SpimDataException | IOException e)
			{
				e.printStackTrace();
				System.out.println( "Failed to write metadata for '" + n5Dataset + "': " + e );
				return null;
			}

			System.out.println( "Done writing BDV metadata.");
		}

		final SparkConf conf = new SparkConf().setAppName("AffineFusion");
		// TODO: REMOVE
		//conf.set("spark.driver.bindAddress", "127.0.0.1");

		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		final JavaRDD<long[][]> rdd = sc.parallelize( grid );

		final long time = System.currentTimeMillis();
		rdd.foreach( new WriteSuperBlock(
				xmlPath,
				preserveAnisotropy,
				anisotropyFactor,
				boundingBox,
				n5Path,
				n5Dataset,
				bdvString,
				storageType,
				serializedViewIds,
				uint8,
				uint16,
				minIntensity,
				range ) );

		if ( this.downsamplings != null )
		{
			// TODO: run common downsampling code (affine, non-rigid, downsampling-only)
			Downsampling.createDownsampling(
					n5Path,
					n5Dataset,
					driverVolumeWriter,
					dimensions,
					storageType,
					blockSize,
					dataType,
					compression,
					downsamplings,
					bdvString != null,
					sc );
		}

		sc.close();

		// close HDF5 writer
		if ( N5Util.hdf5DriverVolumeWriter != null )
			N5Util.hdf5DriverVolumeWriter.close();
		else
			System.out.println( "Saved, e.g. view with './n5-view -i " + n5Path + " -d " + n5Dataset );

		System.out.println( "done, took: " + (System.currentTimeMillis() - time ) + " ms." );

		return null;
	}

	public static void main(final String... args) throws SpimDataException {

		//final XmlIoSpimData io = new XmlIoSpimData();
		//final SpimData spimData = io.load( "/Users/preibischs/Documents/Microscopy/Stitching/Truman/standard/output/dataset.xml" );
		//BdvFunctions.show( spimData );
		//SimpleMultiThreading.threadHaltUnClean();

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new SparkAffineFusion()).execute(args));
	}







	private static class WriteSuperBlock implements VoidFunction< long[][] >
	{

		private final String xmlPath;

		private final boolean preserveAnisotropy;

		private final double anisotropyFactor;

		private final long[] minBB;

		private final long[] maxBB;

		private final String n5Path;

		private final String n5Dataset;

		private final String bdvString;

		private final StorageType storageType;

		private final int[][] serializedViewIds;

		private final boolean uint8;

		private final boolean uint16;

		private final double minIntensity;

		private final double range;


		public WriteSuperBlock(
				final String xmlPath,
				final boolean preserveAnisotropy,
				final double anisotropyFactor,
				final BoundingBox boundingBox,
				final String n5Path,
				final String n5Dataset,
				final String bdvString,
				final StorageType storageType,
				final int[][] serializedViewIds,
				final boolean uint8,
				final boolean uint16,
				final double minIntensity,
				final double range )
		{
			this.xmlPath = xmlPath;
			this.preserveAnisotropy = preserveAnisotropy;
			this.anisotropyFactor = anisotropyFactor;
			this.minBB = boundingBox.minAsLongArray();
			this.maxBB = boundingBox.maxAsLongArray();
			this.n5Path = n5Path;
			this.n5Dataset = n5Dataset;
			this.bdvString = bdvString;
			this.storageType = storageType;
			this.serializedViewIds = serializedViewIds;
			this.uint8 = uint8;
			this.uint16 = uint16;
			this.minIntensity = minIntensity;
			this.range = range;
		}

		@Override
		public void call( final long[][] gridBlock ) throws Exception
		{
			// The min coordinates of the block that this job renders (in pixels)
			final long[] currentBlockOffset = gridBlock[ 0 ];

			// The size of the block that this job renders (in pixels)
			final long[] currentBlockSize = gridBlock[ 1 ];

			// The min grid coordinate of the block that this job renders, in units of the output grid.
			// Note, that the block that is rendered may cover multiple output grid cells.
			final long[] outputGridOffset = gridBlock[ 2 ];





			// --------------------------------------------------------
			// initialization work that is happening in every job,
			// independent of gridBlock parameters
			// --------------------------------------------------------

			// custom serialization
			final SpimData2 dataLocal = Spark.getSparkJobSpimData2("", xmlPath);
			final List< ViewId > viewIds = Spark.deserializeViewIds( serializedViewIds );

			// If requested, preserve the anisotropy of the data (output
			// data has same anisotropy as input data) by prepending an
			// affine to each ViewRegistration
			if ( preserveAnisotropy )
			{
				final AffineTransform3D aniso = new AffineTransform3D();
				aniso.set(
						1.0, 0.0, 0.0, 0.0,
						0.0, 1.0, 0.0, 0.0,
						0.0, 0.0, 1.0 / anisotropyFactor, 0.0 );
				final ViewTransformAffine preserveAnisotropy = new ViewTransformAffine( "preserve anisotropy", aniso );

				final ViewRegistrations registrations = dataLocal.getViewRegistrations();
				for ( final ViewId viewId : viewIds )
				{
					final ViewRegistration vr = registrations.getViewRegistration( viewId );
					vr.preconcatenateTransform( preserveAnisotropy );
					vr.updateModel();
				}
			}












			// be smarter, test which ViewIds are actually needed for the block we want to fuse
			final Interval fusedBlock =
					Intervals.translate(
							FinalInterval.createMinSize( currentBlockOffset, currentBlockSize ),
							minBB ); // min of the randomaccessbileinterval

			// recover views to process
			final ArrayList< ViewId > viewIdsLocal = new ArrayList<>();
			final List< Callable< Object > > prefetch = new ArrayList<>();
			for ( final ViewId viewId : viewIds )
			{
				// expand to be conservative ...
				final Interval boundingBoxLocal = ViewUtil.getTransformedBoundingBox( dataLocal, viewId );
				final Interval bounds = Intervals.expand( boundingBoxLocal, 2 );

				if ( ViewUtil.overlaps( fusedBlock, bounds ) )
				{
					// determine which Cells exactly we need to compute the fused block
					final List< PrefetchPixel< ? > > blocks = ViewUtil.findOverlappingBlocks( dataLocal, viewId, fusedBlock );
					if ( !blocks.isEmpty() )
					{
						prefetch.addAll( blocks );
						viewIdsLocal.add( viewId );
					}
				}
			}

			//SimpleMultiThreading.threadWait( 10000 );

			// nothing to save...
			if ( viewIdsLocal.isEmpty() )
				return;

			// prefetch cells: each cell on a separate thread
			final ExecutorService executor = Executors.newFixedThreadPool( prefetch.size() );
			final List< Future< Object > > prefetched = executor.invokeAll( prefetch );
			executor.shutdown();

			final RandomAccessibleInterval< FloatType > source = FusionTools.fuseVirtual(
					dataLocal,
					viewIdsLocal,
					FinalInterval.wrap( minBB, maxBB )
			);

			final N5Writer executorVolumeWriter = N5Util.createWriter( n5Path, storageType );

			if ( uint8 )
			{
				final RandomAccessibleInterval< UnsignedByteType > sourceUINT8 =
						Converters.convert(
								source,(i, o) -> o.setReal( ( i.get() - minIntensity ) / range ),
								new UnsignedByteType());

				final RandomAccessibleInterval< UnsignedByteType > sourceGridBlock = Views.offsetInterval( sourceUINT8, currentBlockOffset, currentBlockSize );
				//N5Utils.saveNonEmptyBlock(sourceGridBlock, n5Writer, n5Dataset, gridBlock[2], new UnsignedByteType());
				N5Utils.saveBlock( sourceGridBlock, executorVolumeWriter, n5Dataset, outputGridOffset );
			}
			else if ( uint16 )
			{
				final RandomAccessibleInterval< UnsignedShortType > sourceUINT16 =
						Converters.convert(
								source,(i, o) -> o.setReal( ( i.get() - minIntensity ) / range ),
								new UnsignedShortType());

				if ( bdvString != null && StorageType.HDF5.equals( storageType ) )
				{
					// TODO (TP): Revise the following .. This is probably fixed now???
					// Tobias: unfortunately I store as short and treat it as unsigned short in Java.
					// The reason is, that when I wrote this, the jhdf5 library did not support unsigned short. It's terrible and should be fixed.
					// https://github.com/bigdataviewer/bigdataviewer-core/issues/154
					// https://imagesc.zulipchat.com/#narrow/stream/327326-BigDataViewer/topic/XML.2FHDF5.20specification
					final RandomAccessibleInterval< ShortType > sourceINT16 =
							Converters.convertRAI( sourceUINT16, (i,o)->o.set( i.getShort() ), new ShortType() );

					final RandomAccessibleInterval< ShortType > sourceGridBlock = Views.offsetInterval( sourceINT16, currentBlockOffset, currentBlockSize );
					N5Utils.saveBlock( sourceGridBlock, executorVolumeWriter, n5Dataset, outputGridOffset );
				}
				else
				{
					final RandomAccessibleInterval< UnsignedShortType > sourceGridBlock = Views.offsetInterval( sourceUINT16, currentBlockOffset, currentBlockSize );
					N5Utils.saveBlock( sourceGridBlock, executorVolumeWriter, n5Dataset, outputGridOffset );
				}
			}
			else
			{
				final RandomAccessibleInterval< FloatType > sourceGridBlock = Views.offsetInterval( source, currentBlockOffset, currentBlockSize );
				N5Utils.saveBlock( sourceGridBlock, executorVolumeWriter, n5Dataset, outputGridOffset );
			}

			// let go of references to the prefetched cells
			prefetched.clear();

			// not HDF5
			if ( N5Util.hdf5DriverVolumeWriter != executorVolumeWriter )
				executorVolumeWriter.close();
		}
	}
}
