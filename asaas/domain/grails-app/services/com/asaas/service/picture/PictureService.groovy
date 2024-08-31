package com.asaas.service.picture

import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.picture.Picture
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.jpeg.JpegDirectory

import grails.transaction.Transactional

import java.awt.Graphics2D
import java.awt.Transparency
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage

import javax.imageio.IIOException
import javax.imageio.ImageIO

import org.apache.commons.lang.SystemUtils
import org.springframework.web.multipart.commons.CommonsMultipartFile

import static java.awt.RenderingHints.*

@Transactional
class PictureService {

	def fileService
    def grailsApplication

	public Picture save(Customer customer, TemporaryFile temporaryFile, CommonsMultipartFile commomFile) {
		try {
			Picture picture = new Picture()
			String fileName = temporaryFile?.originalName ?: commomFile?.getOriginalFilename()
			File originalFile = fileService.getDiskFile(temporaryFile, commomFile)

			AsaasFile asaasFile = fileService.createFile(customer, resize(originalFile, Picture.DEFAULT_IMAGE_SIZE, getFileExtension(fileName)), fileName)
			AsaasFile thumbAsaasFile = fileService.createFile(customer, resize(originalFile, Picture.MICRO_IMAGE_SIZE, getFileExtension(fileName)), "thumb_" + fileName)

			picture.file = asaasFile
			picture.thumbFile = thumbAsaasFile

			picture.save(failOnError: true)
			return picture
		} catch (IIOException ie) {
            AsaasLogger.warn("Erro ao salvar imagem, cliente ${customer.id}", ie)
			throw new BusinessException("Ocorreu um erro ao salvar sua imagem")
		}
	}

    public Picture save(Customer customer, File file) {
        if (!file) return

        Picture picture = new Picture()

        AsaasFile asaasFile = fileService.createFile(customer, file, file.getName())
        picture.file = asaasFile

        picture.save(failOnError: true)
        return picture
    }

	public File resize(File file, Integer maxImageSize, String fileExtension) {
        if (!file.exists()) throw new RuntimeException("PictureService.resize >> O arquivo [${file.getAbsolutePath()}] não existe em disco, impossibilitando sua leitura")

		BufferedImage src

		try {
			src = (getImageWithCorrectOrientation(file) ?: ImageIO.read(file))
		} catch (Exception e) {
			src = ImageIO.read(file)
		}

		if (!src) return validateOriginalFileSize(file)

		BufferedImage newImage

		if (maxImageSize < Picture.DEFAULT_IMAGE_SIZE) {
			newImage = scaleImageIncrementally(src, maxImageSize)
		} else {
			newImage = scaleImage(src, maxImageSize)
		}

		File newImageFile = File.createTempFile("temp", ".tmp")
		ImageIO.write(newImage, fileExtension, newImageFile)

		return newImageFile
	}

    public File resizeMaintainingAspectRatio(AsaasFile asaasFile, Integer width, Integer height, Map params) {
        if (!width && !height) throw new IllegalArgumentException("Uma nova largura e/ou altura deve ser informada.")
        String newWidthAndHeight = "${width ?: ""}x${height ?: ""}"
        String extentWidthAndHeight = "${params.extentWidth ?: ""}x${params.extentHeight ?: ""}"

        File jpgLogoFile = convertToJpg(asaasFile.getFile(), params)

        File resizedFile = File.createTempFile("temp", ".jpg")

        List<String> commandList = []
        if (SystemUtils.IS_OS_WINDOWS) {
            commandList.add(grailsApplication.config.imageMagick.runnableAbsolutePath)
            commandList.add("convert")
        } else {
            commandList.add("${grailsApplication.config.imageMagick.runnableAbsolutePath}convert")
        }

        commandList.add(jpgLogoFile.absolutePath)
        String commandArgs = "-resize ${newWidthAndHeight} -background ${params.backgroundColorToResize ?: "white"} -alpha Background -compose Copy -gravity center -extent ${extentWidthAndHeight ?: newWidthAndHeight} -quality 100"
        commandList.addAll(commandArgs.split(" "))
        commandList.add(resizedFile.absolutePath)

        InputStream stream = Utils.executeCommandLine(commandList)
        resizedFile.append(stream)

        return resizedFile
    }

    public File resizeMaintainingAspectRatio(AsaasFile asaasFile, Integer width, Integer height) {
        if (!width && !height) throw new IllegalArgumentException("Uma nova largura e/ou altura deve ser informada.")
        String newWidthAndHeight = "${width ?: ""}x${height ?: ""}"

        File resizedFile = File.createTempFile("temp", ".png")

        List<String> commandList = []
        if (SystemUtils.IS_OS_WINDOWS) {
            commandList.add(grailsApplication.config.imageMagick.runnableAbsolutePath)
            commandList.add("convert")
        } else {
            commandList.add("${grailsApplication.config.imageMagick.runnableAbsolutePath}convert")
        }

        commandList.add(asaasFile.getFile().absolutePath)
        String commandArgs = "-resize ${newWidthAndHeight} -alpha Background -compose Copy -gravity center -quality 100"
        commandList.addAll(commandArgs.split(" "))
        commandList.add(resizedFile.absolutePath)

        InputStream stream = Utils.executeCommandLine(commandList)
        resizedFile.append(stream)

        return resizedFile
    }

    private File convertToJpg(File file, Map params) {
        File jpgFile = File.createTempFile("temp", ".jpg")

        List<String> commandList = []
        if (SystemUtils.IS_OS_WINDOWS) {
            commandList.add(grailsApplication.config.imageMagick.runnableAbsolutePath)
            commandList.add("convert")
        } else {
            commandList.add("${grailsApplication.config.imageMagick.runnableAbsolutePath}convert")
        }

        commandList.add(file.absolutePath)
        String commandArgs = "-background ${params.backgroundColorToJpg ?: "white"} -alpha Background"
        commandList.addAll(commandArgs.split(" "))
        commandList.add(jpgFile.absolutePath)

        InputStream stream = Utils.executeCommandLine(commandList)
        jpgFile.append(stream)

        return jpgFile
    }

    private File validateOriginalFileSize(File file) {
        if (file.length() > 500000) { //500kb
			throw new BusinessException("Envie um arquivo com tamanho até 500kb.")
		}
        return file
    }

	private BufferedImage scaleImageIncrementally(BufferedImage src, Integer targetSize) {
		Double scale = calculateScale(targetSize, src.width, src.height)

		Integer targetWidth = (src.width * scale).toInteger()
		Integer targetHeight = (src.height * scale).toInteger()
		Boolean flushSource = false

		while(src.width > targetWidth || src.height > targetHeight) {
			Integer fraction = (src.width > Picture.FRACTION_SIZE_BREAKPOINT || src.height > Picture.FRACTION_SIZE_BREAKPOINT) ? 2 : 7

			Integer currentWidth = src.width
			Integer currentHeight = src.height

			if (currentWidth > targetWidth) {
				currentWidth -= (currentWidth / fraction);

				if (currentWidth < targetWidth) currentWidth = targetWidth;
			}

			if (currentHeight > targetHeight) {
				currentHeight -= (currentHeight / fraction);

				if (currentHeight < targetHeight) currentHeight = targetHeight;
			}

			BufferedImage incrementalImage = scaleImage(src, currentWidth, currentHeight)
			src = incrementalImage

			if (flushSource) src.flush();
			else flushSource = true
		}
		return src
	}

	private BufferedImage scaleImage(BufferedImage src, Integer targetSize) {
		Double scale = calculateScale(targetSize, src.width, src.height)

		if (src.width <= targetSize && src.height <= targetSize) return src

		Integer newWidth = (src.width * scale).toInteger()
		Integer newHeight = (src.height * scale).toInteger()
		return scaleImage(src, newWidth, newHeight)
	}

	private BufferedImage scaleImage(BufferedImage src, int newWidth, int newHeight) {
		def imgType = src.getTransparency() == Transparency.OPAQUE ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB

		BufferedImage image = new BufferedImage(newWidth, newHeight, imgType)
		Graphics2D resultGraphics = image.createGraphics()
		resultGraphics.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BICUBIC)
		resultGraphics.drawImage(src, 0, 0, newWidth, newHeight, null)
		resultGraphics.dispose()

		return image
	}

	private String getFileExtension(String fileName) {
		return fileName ? fileName.tokenize(".").last() : ""
	}

	private Double calculateScale(Integer targetSize, Integer width, Integer height) {
		if (width > height) return targetSize / width
		return targetSize / height
	}

	private BufferedImage getImageWithCorrectOrientation(File src) {
		//Lógica baseada em https://stackoverflow.com/questions/21951892/how-to-determine-and-auto-rotate-images

        BufferedImage originalImage = ImageIO.read(src)

        Metadata metadata = ImageMetadataReader.readMetadata(src)
        ExifIFD0Directory exifIFD0Directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class)
        if (!exifIFD0Directory) return originalImage

        JpegDirectory jpegDirectory = (JpegDirectory) metadata.getFirstDirectoryOfType(JpegDirectory.class)
        if (!jpegDirectory) return originalImage

		int orientation = exifIFD0Directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
		int width = jpegDirectory.getImageWidth();
		int height = jpegDirectory.getImageHeight();

		AffineTransform affineTransform = new AffineTransform();

		int newWidth = originalImage.getWidth()
		int newHeight = originalImage.getHeight()

		switch (orientation) {
		case 1:
			break;
		case 2: // Flip X
			affineTransform.scale(-1.0, 1.0);
			affineTransform.translate(-width, 0);
			break;
		case 3: // PI rotation
			affineTransform.translate(width, height);
			affineTransform.rotate(Math.PI);
			break;
		case 4: // Flip Y
			affineTransform.scale(1.0, -1.0);
			affineTransform.translate(0, -height);
			break;
		case 5: // - PI/2 and Flip X
			affineTransform.rotate(-Math.PI / 2);
			affineTransform.scale(-1.0, 1.0);
			break;
		case 6: // -PI/2 and -width
			affineTransform.translate(height, 0);
			affineTransform.rotate(Math.PI / 2);
			newWidth = originalImage.getHeight()
			newHeight = originalImage.getWidth()
			break;
		case 7: // PI/2 and Flip
			affineTransform.scale(-1.0, 1.0);
			affineTransform.translate(-height, 0);
			affineTransform.translate(0, width);
			affineTransform.rotate(3 * Math.PI / 2);
			break;
		case 8: // PI / 2
			affineTransform.translate(0, width);
			affineTransform.rotate(3 * Math.PI / 2);
			newWidth = originalImage.getHeight()
			newHeight = originalImage.getWidth()
			break;
		default:
			break;
		}

		AffineTransformOp affineTransformOp = new AffineTransformOp(affineTransform, AffineTransformOp.TYPE_BILINEAR)
		BufferedImage destinationImage = new BufferedImage(newWidth, newHeight, originalImage.getType());
		destinationImage = affineTransformOp.filter(originalImage, destinationImage);

		return destinationImage
	}
}
