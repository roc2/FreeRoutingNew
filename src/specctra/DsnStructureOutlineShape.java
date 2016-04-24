package specctra;

import freert.planar.PlaPoint;
import freert.planar.ShapePolyline;
import freert.planar.ShapeTile;
import freert.planar.ShapeTileBox;

/**
 * Used to separate the holes in the outline.
 */
public class DsnStructureOutlineShape
   {
   final ShapePolyline shape;
   final ShapeTileBox bounding_box;
   final ShapeTile[] convex_shapes;
   boolean is_hole;

   public DsnStructureOutlineShape(ShapePolyline p_shape)
      {
      shape = p_shape;
      bounding_box = p_shape.bounding_box();
      convex_shapes = p_shape.split_to_convex();
      is_hole = false;
      }

   /**
    * Returns true, if this shape contains all corners of p_other_shape.
    */
   boolean contains_all_corners(DsnStructureOutlineShape p_other_shape)
      {
      if ( convex_shapes == null)
         {
         // calculation of the convex shapes failed
         return false;
         }
      
      int corner_count = p_other_shape.shape.border_line_count();
      for (int i = 0; i < corner_count; ++i)
         {
         PlaPoint curr_corner = p_other_shape.shape.corner(i);
         boolean is_contained = false;
         for (int j = 0; j < this.convex_shapes.length; ++j)
            {
            if (this.convex_shapes[j].contains(curr_corner))
               {
               is_contained = true;
               break;
               }
            }
         if (!is_contained)
            {
            return false;
            }
         }
      return true;
      }
   }